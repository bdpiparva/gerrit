// Copyright (C) 2016 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.git.strategy;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.gerrit.server.notedb.ReviewerStateInternal.REVIEWER;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.gerrit.common.data.SubmitRecord;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.ChangeMessage;
import com.google.gerrit.reviewdb.client.LabelId;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.PatchSetApproval;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.git.BatchUpdate;
import com.google.gerrit.server.git.BatchUpdate.ChangeContext;
import com.google.gerrit.server.git.BatchUpdate.Context;
import com.google.gerrit.server.git.BatchUpdate.RepoContext;
import com.google.gerrit.server.git.CodeReviewCommit;
import com.google.gerrit.server.git.IntegrationException;
import com.google.gerrit.server.git.LabelNormalizer;
import com.google.gerrit.server.git.ProjectConfig;
import com.google.gerrit.server.notedb.ChangeUpdate;
import com.google.gerrit.server.project.ProjectState;
import com.google.gwtorm.server.OrmException;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

abstract class SubmitStrategyOp extends BatchUpdate.Op {
  private static final Logger log =
      LoggerFactory.getLogger(SubmitStrategyOp.class);

  protected final SubmitStrategy.Arguments args;
  protected final CodeReviewCommit toMerge;

  private ReceiveCommand command;
  private PatchSetApproval submitter;
  private ObjectId mergeResultRev;
  private PatchSet mergedPatchSet;
  private Change updatedChange;

  protected SubmitStrategyOp(SubmitStrategy.Arguments args,
      CodeReviewCommit toMerge) {
    this.args = args;
    this.toMerge = toMerge;
  }

  final Change.Id getId() {
    return toMerge.change().getId();
  }

  final CodeReviewCommit getCommit() {
    return toMerge;
  }

  protected final Branch.NameKey getDest() {
    return toMerge.change().getDest();
  }

  protected final Project.NameKey getProject() {
    return getDest().getParentKey();
  }

  @Override
  public final void updateRepo(RepoContext ctx) throws Exception {
    // Run the submit strategy implementation and record the merge tip state so
    // we can create the ref update.
    CodeReviewCommit tipBefore = args.mergeTip.getCurrentTip();
    updateRepoImpl(ctx);
    CodeReviewCommit tipAfter = args.mergeTip.getCurrentTip();

    if (Objects.equals(tipBefore, tipAfter)) {
      return;
    } else if (tipAfter == null) {
      logDebug("No merge tip, no update to perform");
      return;
    }

    checkProjectConfig(ctx, tipAfter);

    // Needed by postUpdate, at which point mergeTip will have advanced further,
    // so it's easier to just snapshot the command.
    command = new ReceiveCommand(
        firstNonNull(tipBefore, ObjectId.zeroId()),
        tipAfter,
        getDest().get());
    ctx.addRefUpdate(command);
  }

  private void checkProjectConfig(RepoContext ctx, CodeReviewCommit commit)
      throws IntegrationException {
    String refName = getDest().get();
    if (RefNames.REFS_CONFIG.equals(refName)) {
      logDebug("Loading new configuration from {}", RefNames.REFS_CONFIG);
      try {
        ProjectConfig cfg = new ProjectConfig(getProject());
        cfg.load(ctx.getRepository(), commit);
      } catch (Exception e) {
        throw new IntegrationException("Submit would store invalid"
            + " project configuration " + commit.name() + " for "
            + getProject(), e);
      }
    }
  }

  @Override
  public final boolean updateChange(ChangeContext ctx) throws Exception {
    toMerge.setControl(ctx.getControl()); // Update change and notes from ctx.
    updateChangeImpl(ctx);

    Change c = ctx.getChange();
    Change.Id id = c.getId();
    try {
      CodeReviewCommit commit = args.commits.get(id);
      CommitMergeStatus s = commit != null ? commit.getStatusCode() : null;
      logDebug("Status of change {} ({}) on {}: {}", id, commit.name(),
          c.getDest(), s);
      checkState(s != null,
          "status not set for change %s; expected to previously fail fast",
          id);
      setApproval(ctx, args.caller);

      mergeResultRev = args.mergeTip != null
          ? args.mergeTip.getMergeResults().get(commit) : null;
      String txt = s.getMessage();

      ChangeMessage msg;
      if (s == CommitMergeStatus.CLEAN_MERGE) {
        msg = message(ctx, commit.getPatchsetId(), txt + getByAccountName());
      } else if (s == CommitMergeStatus.CLEAN_REBASE
          || s == CommitMergeStatus.CLEAN_PICK) {
        msg = message(ctx, commit.getPatchsetId(),
            txt + " as " + commit.name() + getByAccountName());
      } else if (s == CommitMergeStatus.ALREADY_MERGED) {
        msg = null;
      } else {
        throw new IllegalStateException("unexpected status " + s +
            " for change " + c.getId() + "; expected to previously fail fast");
      }
      setMerged(ctx, msg);
    } catch (OrmException err) {
      String msg = "Error updating change status for " + id;
      log.error(msg, err);
      args.commits.logProblem(id, msg);
      // It's possible this happened before updating anything in the db, but
      // it's hard to know for sure, so just return true below to be safe.
    }
    updatedChange = c;
    return true;
  }

  private void setApproval(ChangeContext ctx, IdentifiedUser user)
      throws OrmException {
    Change.Id id = ctx.getChange().getId();
    List<SubmitRecord> records = args.commits.getSubmitRecords(id);
    PatchSet.Id oldPsId = toMerge.getPatchsetId();
    PatchSet.Id newPsId = ctx.getChange().currentPatchSetId();

    logDebug("Add approval for " + id);
    ChangeUpdate origPsUpdate = ctx.getUpdate(oldPsId);
    origPsUpdate.putReviewer(user.getAccountId(), REVIEWER);
    LabelNormalizer.Result normalized = approve(ctx, origPsUpdate);

    ChangeUpdate newPsUpdate = ctx.getUpdate(newPsId);
    newPsUpdate.merge(records);
    // If the submit strategy created a new revision (rebase, cherry-pick), copy
    // approvals as well.
    if (!newPsId.equals(oldPsId)) {
      saveApprovals(normalized, ctx, newPsUpdate, true);
      submitter = convertPatchSet(newPsId).apply(submitter);
    }
  }

  private LabelNormalizer.Result approve(ChangeContext ctx, ChangeUpdate update)
      throws OrmException {
    PatchSet.Id psId = update.getPatchSetId();
    Map<PatchSetApproval.Key, PatchSetApproval> byKey = Maps.newHashMap();
    for (PatchSetApproval psa : args.approvalsUtil.byPatchSet(
        ctx.getDb(), ctx.getControl(), psId)) {
      byKey.put(psa.getKey(), psa);
    }

    submitter = new PatchSetApproval(
          new PatchSetApproval.Key(
              psId,
              ctx.getUser().getAccountId(),
              LabelId.SUBMIT),
              (short) 1, ctx.getWhen());
    byKey.put(submitter.getKey(), submitter);
    submitter.setValue((short) 1);
    submitter.setGranted(ctx.getWhen());

    // Flatten out existing approvals for this patch set based upon the current
    // permissions. Once the change is closed the approvals are not updated at
    // presentation view time, except for zero votes used to indicate a reviewer
    // was added. So we need to make sure votes are accurate now. This way if
    // permissions get modified in the future, historical records stay accurate.
    LabelNormalizer.Result normalized =
        args.labelNormalizer.normalize(ctx.getControl(), byKey.values());
    update.putApproval(submitter.getLabel(), submitter.getValue());
    saveApprovals(normalized, ctx, update, false);
    return normalized;
  }

  private void saveApprovals(LabelNormalizer.Result normalized,
      ChangeContext ctx, ChangeUpdate update, boolean includeUnchanged)
      throws OrmException {
    PatchSet.Id psId = update.getPatchSetId();
    ctx.getDb().patchSetApprovals().upsert(
        convertPatchSet(normalized.getNormalized(), psId));
    ctx.getDb().patchSetApprovals().delete(
        convertPatchSet(normalized.deleted(), psId));
    for (PatchSetApproval psa : normalized.updated()) {
      update.putApprovalFor(psa.getAccountId(), psa.getLabel(), psa.getValue());
    }
    for (PatchSetApproval psa : normalized.deleted()) {
      update.removeApprovalFor(psa.getAccountId(), psa.getLabel());
    }

    // TODO(dborowitz): Don't use a label in notedb; just check when status
    // change happened.
    for (PatchSetApproval psa : normalized.unchanged()) {
      if (includeUnchanged || psa.isSubmit()) {
        logDebug("Adding submit label " + psa);
        update.putApprovalFor(
            psa.getAccountId(), psa.getLabel(), psa.getValue());
      }
    }
  }

  private static Function<PatchSetApproval, PatchSetApproval>
      convertPatchSet(final PatchSet.Id psId) {
    return new Function<PatchSetApproval, PatchSetApproval>() {
      @Override
      public PatchSetApproval apply(PatchSetApproval in) {
        if (in.getPatchSetId().equals(psId)) {
          return in;
        } else {
          return new PatchSetApproval(psId, in);
        }
      }
    };
  }

  private static Iterable<PatchSetApproval> convertPatchSet(
      Iterable<PatchSetApproval> approvals, PatchSet.Id psId) {
    return Iterables.transform(approvals, convertPatchSet(psId));
  }

  private String getByAccountName() {
    checkNotNull(submitter,
        "getByAccountName called before submitter populated");
    Account account =
        args.accountCache.get(submitter.getAccountId()).getAccount();
    if (account != null && account.getFullName() != null) {
      return " by " + account.getFullName();
    }
    return "";
  }

  private ChangeMessage message(ChangeContext ctx, PatchSet.Id psId,
      String body) {
    checkNotNull(psId);
    String uuid;
    try {
      uuid = ChangeUtil.messageUUID(ctx.getDb());
    } catch (OrmException e) {
      return null;
    }
    ChangeMessage m = new ChangeMessage(
        new ChangeMessage.Key(psId.getParentKey(), uuid),
        null, ctx.getWhen(), psId);
    m.setMessage(body);
    return m;
  }

  private void setMerged(ChangeContext ctx, ChangeMessage msg)
      throws OrmException {
    Change c = ctx.getChange();
    ReviewDb db = ctx.getDb();
    logDebug("Setting change {} merged", c.getId());
    // TODO(dborowitz): Use PatchSetUtil? But we don't have a recent notes.
    mergedPatchSet = db.patchSets().get(c.currentPatchSetId());
    c.setStatus(Change.Status.MERGED);
    c.setSubmissionId(args.submissionId);
    ctx.saveChange();

    // TODO(dborowitz): We need to be able to change the author of the message,
    // which is not the user from the update context. addMergedMessage was able
    // to do this in the past.
    if (msg != null) {
      args.cmUtil.addChangeMessage(db, ctx.getUpdate(msg.getPatchSetId()), msg);
    }
  }

  @Override
  public final void postUpdate(Context ctx) throws Exception {
    postUpdateImpl(ctx);

    if (command != null) {
      args.tagCache.updateFastForward(
          getProject(),
          command.getRefName(),
          command.getOldId(),
          command.getNewId());
      // TODO(dborowitz): Move to BatchUpdate? Would also allow us to run once
      // per project even if multiple changes to refs/meta/config are submitted.
      if (RefNames.REFS_CONFIG.equals(getDest().get())) {
        args.projectCache.evict(getProject());
        ProjectState p = args.projectCache.get(getProject());
        args.repoManager.setProjectDescription(
            p.getProject().getNameKey(), p.getProject().getDescription());
      }
    }

    // Assume the change must have been merged at this point, otherwise we would
    // have failed fast in one of the other steps.
    try {
      args.mergedSenderFactory.create(getId(), submitter.getAccountId())
          .sendAsync();
    } catch (Exception e) {
      log.error("Cannot email merged notification for " + getId(), e);
    }
    if (mergeResultRev != null) {
      try {
        args.hooks.doChangeMergedHook(updatedChange,
            args.accountCache.get(submitter.getAccountId()).getAccount(),
            mergedPatchSet, ctx.getDb(), mergeResultRev.name());
      } catch (OrmException ex) {
        logError("Cannot run hook for submitted patch set " + getId(), ex);
      }
    }
  }

  /**
   * @see #updateRepo(RepoContext)
   * @param ctx
   */
  protected void updateRepoImpl(RepoContext ctx) throws Exception {
  }

  /**
   * @see #updateChange(ChangeContext)
   * @param ctx
   */
  protected void updateChangeImpl(ChangeContext ctx) throws Exception {
  }

  /**
   * @see #postUpdate(Context)
   * @param ctx
   */
  protected void postUpdateImpl(Context ctx) throws Exception {
  }

  protected final void logDebug(String msg, Object... args) {
    if (log.isDebugEnabled()) {
      log.debug("[" + this.args.submissionId + "]" + msg, args);
    }
  }

  protected final void logWarn(String msg, Throwable t) {
    if (log.isWarnEnabled()) {
      log.warn("[" + args.submissionId + "]" + msg, t);
    }
  }

  protected void logError(String msg, Throwable t) {
    if (log.isErrorEnabled()) {
      if (t != null) {
        log.error("[" + args.submissionId + "]" + msg, t);
      } else {
        log.error("[" + args.submissionId + "]" + msg);
      }
    }
  }

  protected void logError(String msg) {
    logError(msg, null);
  }
}
