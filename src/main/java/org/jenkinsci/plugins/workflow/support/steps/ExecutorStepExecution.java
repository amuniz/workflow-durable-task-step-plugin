package org.jenkinsci.plugins.workflow.support.steps;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.EnvVars;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Main;
import hudson.Util;
import hudson.console.ModelHyperlinkNote;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.PeriodicWork;
import hudson.model.Queue;
import hudson.model.ResourceList;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.model.User;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueListener;
import hudson.model.queue.SubTask;
import hudson.remoting.ChannelClosedException;
import hudson.remoting.RequestAbortedException;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.AccessControlled;
import hudson.security.Permission;
import hudson.slaves.OfflineCause;
import hudson.slaves.WorkspaceList;
import java.io.IOException;
import java.io.Serializable;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import static java.util.logging.Level.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import jenkins.model.CauseOfInterruption;
import jenkins.model.Jenkins;
import jenkins.model.Jenkins.MasterComputer;
import jenkins.model.NodeListener;
import jenkins.model.queue.AsynchronousExecution;
import jenkins.security.QueueItemAuthenticator;
import jenkins.security.QueueItemAuthenticatorProvider;
import jenkins.util.SystemProperties;
import jenkins.util.Timer;
import org.acegisecurity.Authentication;
import org.jenkinsci.plugins.durabletask.executors.ContinuableExecutable;
import org.jenkinsci.plugins.durabletask.executors.ContinuedTask;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.QueueItemAction;
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionList;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecution;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.durable_task.Messages;
import org.jenkinsci.plugins.workflow.support.actions.WorkspaceActionImpl;
import org.jenkinsci.plugins.workflow.support.concurrent.Timeout;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;
import org.springframework.security.access.AccessDeniedException;

public class ExecutorStepExecution extends AbstractStepExecutionImpl {

    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "deliberately mutable")
    @Restricted(value = NoExternalUse.class)
    public static long TIMEOUT_WAITING_FOR_NODE_MILLIS = SystemProperties.getLong("org.jenkinsci.plugins.workflow.support.pickles.ExecutorPickle.timeoutForNodeMillis", Main.isUnitTest ? /* fail faster */ TimeUnit.SECONDS.toMillis(15) : TimeUnit.MINUTES.toMillis(5));

    private final ExecutorStep step;
    private ExecutorStepDynamicContext state;

    /**
     * Needed for {@link BodyExecution#cancel} in certain scenarios.
     */
    private @CheckForNull BodyExecution body;

    ExecutorStepExecution(StepContext context, ExecutorStep step) {
        super(context);
        this.step = step;
    }

    /**
     * General strategy of this step.
     *
     * 1. schedule {@link PlaceholderTask} into the {@link Queue} (what this method does)
     * 2. when {@link PlaceholderTask} starts running, invoke the closure
     * 3. when the closure is done, let {@link PlaceholderTask} complete
     */
    @Override
    public boolean start() throws Exception {
        final PlaceholderTask task = new PlaceholderTask(getContext(), step.getLabel());
        Queue.WaitingItem waitingItem = Queue.getInstance().schedule2(task, 0).getCreateItem();
        if (waitingItem == null) {
            // There can be no duplicates. But could be refused if a QueueDecisionHandler rejects it for some odd reason.
            throw new IllegalStateException("failed to schedule task");
        }
        getContext().get(FlowNode.class).addAction(new QueueItemActionImpl(waitingItem.getId()));

        Timer.get().schedule(() -> {
            Queue.Item item = Queue.getInstance().getItem(task);
            if (item != null) {
                TaskListener listener;
                try {
                    listener = getContext().get(TaskListener.class);
                } catch (Exception x) { // IOException, InterruptedException
                    LOGGER.log(FINE, "could not print message to build about " + item + "; perhaps it is already completed", x);
                    return;
                }
                listener.getLogger().println("Still waiting to schedule task");
                CauseOfBlockage cob = item.getCauseOfBlockage();
                if (cob != null) {
                    cob.print(listener);
                }
            }
        }, 15, TimeUnit.SECONDS);
        return false;
    }

    @Override
    public void stop(@NonNull Throwable cause) throws Exception {
        Queue.Item[] items;
        try (ACLContext as = ACL.as(ACL.SYSTEM)) {
            items = Queue.getInstance().getItems();
        }
        LOGGER.log(FINE, "stopping one of {0}", Arrays.asList(items));
        StepContext context = getContext();
        for (Queue.Item item : items) {
            // if we are still in the queue waiting to be scheduled, just retract that
            if (item.task instanceof PlaceholderTask) {
                PlaceholderTask task = (PlaceholderTask) item.task;
                if (task.context.equals(context)) {
                    task.stopping = true;
                    if (Queue.getInstance().cancel(item)) {
                        LOGGER.fine(() -> "canceled " + item);
                    } else {
                        LOGGER.warning(() -> "failed to cancel " + item + " in response to " + cause);
                    }
                    break;
                } else {
                    LOGGER.log(FINE, "no match on {0} with {1} vs. {2}", new Object[] {item, task.context, context});
                }
            } else {
                LOGGER.log(FINE, "no match on {0}", item);
            }
        }
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j != null) {
            // if we are already running, kill the ongoing activities, which releases PlaceholderExecutable from its sleep loop
            // Similar to Executor.of, but distinct since we do not have the Executable yet:
            COMPUTERS: for (Computer c : j.getComputers()) {
                for (Executor e : c.getExecutors()) {
                    Queue.Executable exec = e.getCurrentExecutable();
                    if (exec instanceof PlaceholderTask.PlaceholderExecutable) {
                        StepContext actualContext = ((PlaceholderTask.PlaceholderExecutable) exec).getParent().context;
                        if (actualContext.equals(context)) {
                            PlaceholderTask.finish(((PlaceholderTask.PlaceholderExecutable) exec).getParent().cookie);
                            LOGGER.log(FINE, "canceling {0}", exec);
                            break COMPUTERS;
                        } else {
                            LOGGER.log(FINE, "no match on {0} with {1} vs. {2}", new Object[] {exec, actualContext, context});
                        }
                    } else {
                        LOGGER.log(FINE, "no match on {0}", exec);
                    }
                }
            }
        }
        // Whether or not either of the above worked (and they would not if for example our item were canceled), make sure we die.
        super.stop(cause);
    }

    @Override public void onResume() {
        try {
            if (state == null) {
                Run<?, ?> run = getContext().get(Run.class);
                LOGGER.fine(() -> "No ExecutorStepDynamicContext found for node block in " + run + "; perhaps loading from a historical build record, hoping for the best");
                return;
            }
            state.resume(getContext());
        } catch (Exception x) { // JENKINS-40161
            try {
                // Use stop to make sure we clean up the queue and executors.
                stop(x);
            } catch (Exception x2) {
                // I think this should be unreachable.
                x.addSuppressed(x2);
                getContext().onFailure(x);
            }
        }
    }

    @Override public String getStatus() {
        // Yet another copy of the same logic; perhaps this should be factored into some method returning a union of Queue.Item and PlaceholderExecutable?
        for (Queue.Item item : Queue.getInstance().getItems()) {
            if (item.task instanceof PlaceholderTask && ((PlaceholderTask) item.task).context.equals(getContext())) {
                return "waiting for " + item.task.getFullDisplayName() + " to be scheduled; blocked: " + item.getWhy();
            }
        }
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j != null) {
            for (Computer c : j.getComputers()) {
                for (Executor e : c.getExecutors()) {
                    Queue.Executable exec = e.getCurrentExecutable();
                    if (exec instanceof PlaceholderTask.PlaceholderExecutable && ((PlaceholderTask.PlaceholderExecutable) exec).getParent().context.equals(getContext())) {
                        return "running on " + c.getName();
                    }
                }
            }
        }
        return "node block appears to be neither running nor scheduled";
    }

    @Extension public static class CancelledItemListener extends QueueListener {

        @Override public void onLeft(Queue.LeftItem li) {
            if (li.isCancelled()) {
                if (li.task instanceof PlaceholderTask) {
                    PlaceholderTask task = (PlaceholderTask) li.task;
                    if (!task.stopping) {
                        if (LOGGER.isLoggable(Level.FINE)) {
                            LOGGER.log(Level.FINE, null, new Throwable(li.task + " was cancelled"));
                        }
                        task.context.onFailure(new FlowInterruptedException(Result.ABORTED, true, new QueueTaskCancelled()));
                    }
                }
            }
        }

    }

    /**
     * Looks for executions whose {@link #getStatus} would be neither running nor scheduled, and cancels them.
     */
    @Extension public static final class AnomalousStatus extends PeriodicWork {

        @Override public long getRecurrencePeriod() {
            return Duration.ofMinutes(30).toMillis();
        }

        @Override public long getInitialDelay() {
            // Do not run too soon after startup, in case things are still loading, agents are still reattaching, etc.
            return Duration.ofMinutes(15).toMillis();
        }

        /**
         * Tasks considered to be in an anomalous status the last time we ran.
         */
        private Set<StepContext> anomalous = Set.of();

        @Override protected void doRun() throws Exception {
            LOGGER.fine("checking");
            Set<StepContext> knownTasks = new HashSet<>();
            for (Queue.Item item : Queue.getInstance().getItems()) {
                if (item.task instanceof PlaceholderTask) {
                    LOGGER.fine(() -> "pending " + item);
                    knownTasks.add(((PlaceholderTask) item.task).context);
                }
            }
            Jenkins j = Jenkins.getInstanceOrNull();
            if (j != null) {
                for (Computer c : j.getComputers()) {
                    for (Executor e : c.getExecutors()) {
                        Queue.Executable exec = e.getCurrentExecutable();
                        if (exec instanceof PlaceholderTask.PlaceholderExecutable) {
                            LOGGER.fine(() -> "running " + exec);
                            knownTasks.add(((PlaceholderTask.PlaceholderExecutable) exec).getParent().context);
                        }
                    }
                }
            }
            Set<StepContext> newAnomalous = new HashSet<>();
            StepExecution.applyAll(ExecutorStepExecution.class, exec -> {
                StepContext ctx = exec.getContext();
                if (!knownTasks.contains(ctx)) {
                    LOGGER.warning(() -> "do not know about " + ctx);
                    if (anomalous.contains(ctx)) {
                        try {
                            ctx.get(TaskListener.class).error("node block still appears to be neither running nor scheduled; cancelling");
                        } catch (IOException | InterruptedException x) {
                            LOGGER.log(Level.WARNING, null, x);
                        }
                        ctx.onFailure(new FlowInterruptedException(Result.ABORTED, false, new QueueTaskCancelled()));
                    } else {
                        newAnomalous.add(ctx);
                    }
                } else {
                    LOGGER.fine(() -> "know about " + ctx);
                }
                return null;
            }).get();
            for (StepContext ctx : newAnomalous) {
                ctx.get(TaskListener.class).error("node block appears to be neither running nor scheduled; will cancel if this condition persists");
            }
            LOGGER.fine(() -> "done checking: " + anomalous + " → " + newAnomalous);
            anomalous = newAnomalous;
        }

    }

    public static final class QueueTaskCancelled extends CauseOfInterruption {
        @Override public String getShortDescription() {
            return Messages.ExecutorStepExecution_queue_task_cancelled();
        }
    }

    @Extension public static final class RemovedNodeListener extends NodeListener {
        @Override protected void onDeleted(@NonNull Node node) {
            if (!RemovedNodeCause.ENABLED) {
                return;
            }
            LOGGER.fine(() -> "received node deletion event on " + node.getNodeName());
            Timer.get().schedule(() -> {
                Computer c = node.toComputer();
                if (c == null || c.isOnline()) {
                    LOGGER.fine(() -> "computer for " + node.getNodeName() + " was missing or online, skipping");
                    return;
                }
                LOGGER.fine(() -> "processing node deletion event on " + node.getNodeName());
                for (Executor e : c.getExecutors()) {
                    Queue.Executable exec = e.getCurrentExecutable();
                    if (exec instanceof PlaceholderTask.PlaceholderExecutable) {
                        PlaceholderTask task = ((PlaceholderTask.PlaceholderExecutable) exec).getParent();
                        TaskListener listener;
                        try {
                            listener = task.context.get(TaskListener.class);
                        } catch (Exception x) {
                            LOGGER.log(Level.WARNING, null, x);
                            continue;
                        }
                        task.withExecution(execution -> {
                            BodyExecution body = execution.body;
                            if (body == null) {
                                listener.getLogger().println("Agent " + node.getNodeName() + " was deleted, but do not have a node body to cancel");
                                return;
                            }
                            listener.getLogger().println("Agent " + node.getNodeName() + " was deleted; cancelling node body");
                            if (Util.isOverridden(BodyExecution.class, body.getClass(), "cancel", Throwable.class)) {
                                body.cancel(new FlowInterruptedException(Result.ABORTED, false, new RemovedNodeCause()));
                            } else { // TODO remove once https://github.com/jenkinsci/workflow-cps-plugin/pull/570 is widely deployed
                                body.cancel(new RemovedNodeCause());
                            }
                        });
                    }
                }
            }, TIMEOUT_WAITING_FOR_NODE_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    public static final class RemovedNodeCause extends CauseOfInterruption {
        @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "deliberately mutable")
        public static boolean ENABLED = Boolean.parseBoolean(System.getProperty(ExecutorStepExecution.class.getName() + ".REMOVED_NODE_DETECTION", "true"));
        @Override public String getShortDescription() {
            return "Agent was removed";
        }
    }

    /** Transient handle of a running executor task. */
    private static final class RunningTask {
        /** null until placeholder executable runs */
        @Nullable AsynchronousExecution execution;
        /** null until placeholder executable runs */
        @Nullable Launcher launcher;
    }

    private static final String COOKIE_VAR = "JENKINS_NODE_COOKIE";

    @ExportedBean
    public static final class PlaceholderTask implements ContinuedTask, Serializable, AccessControlled {
        private final StepContext context;
        /** Initially set to {@link ExecutorStep#getLabel}, if any; later switched to actual self-label when block runs. */
        private String label;
        /** Shortcut for {@link #run}. */
        private final String runId;
        /**
         * Unique cookie set once the task starts.
         * Serves multiple purposes:
         * identifies whether we have already invoked the body (since this can be rerun after restart);
         * serves as a key for {@link RunningTasks#runningTasks} and {@link Callback} (cannot just have a doneness flag in {@link PlaceholderTask} because multiple copies might be deserialized);
         * and allows {@link Launcher#kill} to work.
         */
        private String cookie;

        /** {@link Authentication#getName} of user of build, if known. */
        private final @CheckForNull String auth;

        /** Flag to remember that {@link #stop} is being called, so {@link CancelledItemListener} can be suppressed. */
        private transient boolean stopping;

        PlaceholderTask(StepContext context, String label) throws IOException, InterruptedException {
            this.context = context;
            this.label = label;
            runId = context.get(Run.class).getExternalizableId();
            Authentication runningAuth = Jenkins.getAuthentication();
            if (runningAuth.equals(ACL.SYSTEM)) {
                auth = null;
            } else {
                auth = runningAuth.getName();
            }
            LOGGER.log(FINE, "scheduling {0}", this);
        }

        private Object readResolve() {
            if (cookie != null) {
                RunningTasks.get().withRunningTasks(runningTasks -> {
                    // If Jenkins stops while this step is resuming, there may be a PlaceholderTask in the queue as
                    // well as in program.dat for the same step. We want to make sure not to create a second task to
                    // avoid race conditions, so we use putIfAbsent.
                    // TODO: This helps for runningTasks, but other fields like `stopping` may still be problematic.
                    // Should we refactor things to guarantee that the relevant state is a singleton? For example,
                    // introduce a PlaceholderTasksAction that holds a map of a new PlaceholderTaskState class, which
                    // would hold most of what is currently in PlaceholderTask, and then PlaceholderTask would only
                    // hold a `String cookie` field and would look up PlaceholderTaskState via the action so it wouldn't
                    // matter where the task was serialized.
                    runningTasks.putIfAbsent(cookie, new RunningTask());
                });
            }
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(FINE, null, new Exception("deserializing previously scheduled " + this));
            }
            return this;
        }

        /**
         * We cannot keep {@link ExecutorStepExecution} as a serial field of {@link PlaceholderTask}
         * since it could not be serialized via XStream in {@link Queue}.
         * Instead we keep only {@link #context} and look up the execution as needed.
         */
        private void withExecution(Consumer<ExecutorStepExecution> executionCallback) {
            try {
                Futures.addCallback(context.get(FlowExecution.class).getCurrentExecutions(false), new FutureCallback<List<StepExecution>>() {
                    @Override public void onSuccess(List<StepExecution> result) {
                        for (StepExecution execution : result) {
                            if (execution instanceof ExecutorStepExecution && execution.getContext().equals(context)) {
                                executionCallback.accept((ExecutorStepExecution) execution);
                            }
                        }
                    }
                    @Override public void onFailure(Throwable x) {
                        LOGGER.log(Level.WARNING, null, x);
                    }
                }, MoreExecutors.directExecutor());
            } catch (IOException | InterruptedException x) {
                LOGGER.log(Level.WARNING, null, x);
            }
        }

        /**
         * Gives {@link FlowNode}, waiting to be executed  in build {@link Queue}.
         *
         * @return FlowNode instance, could be null.
         */
        public @CheckForNull FlowNode getNode() throws IOException, InterruptedException {
            return context.get(FlowNode.class);
        }

        @Override public Queue.Executable createExecutable() throws IOException {
            return new PlaceholderExecutable();
        }

        @CheckForNull
        public String getCookie() {
            return cookie;
        }

        @Override public Label getAssignedLabel() {
            if (label == null) {
                return null;
            } else if (label.isEmpty()) {
                Jenkins j = Jenkins.getInstanceOrNull();
                if (j == null) {
                    return null;
                }
                return j.getSelfLabel();
            } else {
                return Label.get(label);
            }
        }

        @Override public Node getLastBuiltOn() {
            if (label == null) {
                return null;
            }
            Jenkins j = Jenkins.getInstanceOrNull();
            if (j == null) {
                return null;
            }
            return j.getNode(label);
        }

        @Deprecated
        @Override public boolean isBuildBlocked() {
            return false;
        }

        @Deprecated
        @Override public String getWhyBlocked() {
            return null;
        }

        @Override public CauseOfBlockage getCauseOfBlockage() {
            if (FlowExecutionList.get().isResumptionComplete()) {
                // We only do this if resumption is complete so that we do not load the run and resume its execution in this context in normal scenarios.
                Run<?, ?> run = runForDisplay();
                if (run != null && !run.isLogUpdated()) {
                    if (stopping) {
                        LOGGER.warning(() -> "Refusing to build " + PlaceholderTask.this + " and going to cancel it, even though it was supposedly stopped already, because associated build is complete");
                    } else {
                        stopping = true;
                    }
                    Timer.get().execute(() -> {
                        if (Queue.getInstance().cancel(this)) {
                            LOGGER.warning(() -> "Refusing to build " + PlaceholderTask.this + " and cancelling it because associated build is complete");
                        } else {
                            LOGGER.warning(() -> "Refusing to build " + PlaceholderTask.this + " because associated build is complete, but failed to cancel it");
                        }
                    });
                }
            }
            if (stopping) {
                return new CauseOfBlockage() {
                    @Override
                    public String getShortDescription() {
                        return "Stopping " + getDisplayName();
                    }
                };
            }
            return null;
        }

        @Override public boolean isConcurrentBuild() {
            return false;
        }

        @Override public Collection<? extends SubTask> getSubTasks() {
            return Collections.singleton(this);
        }

        @NonNull
        @Override public Queue.Task getOwnerTask() {
            Jenkins j = Jenkins.getInstanceOrNull();
            if (j != null && runId != null) { // JENKINS-60389 shortcut
                try (ACLContext context = ACL.as(ACL.SYSTEM)) {
                    Job<?, ?> job = j.getItemByFullName(runId.substring(0, runId.lastIndexOf('#')), Job.class);
                    if (job instanceof Queue.Task) {
                        return (Queue.Task) job;
                    }
                }
            }
            Run<?,?> r = runForDisplay();
            if (r != null && r.getParent() instanceof Queue.Task) {
                return (Queue.Task) r.getParent();
            } else {
                return this;
            }
        }

        @Override public Object getSameNodeConstraint() {
            return null;
        }

        /**
         * Something we can use to check abort and read permissions.
         * Normally this will be a {@link Run}.
         * If that has been deleted, we can fall back to the {@link Job}.
         * If things are badly broken, for example if the whole job has been deleted,
         * then as a fallback we use the Jenkins root.
         * This allows an administrator to clean up dead queue items and executor cells.
         */
        @NonNull
        @Override public ACL getACL() {
            try {
                Run<?, ?> r = runForDisplay();
                if (r != null) {
                    return r.getACL();
                } else {
                    Job<?, ?> job = Jenkins.get().getItemByFullName(runId.substring(0, runId.lastIndexOf('#')), Job.class);
                    if (job != null) {
                        return job.getACL();
                    }
                }
            } catch (AccessDeniedException x) {
                // Cannot even read job, so presumably will lack other permissions too.
            } catch (RuntimeException x) {
                LOGGER.log(Level.WARNING, "checking permissions on " + this, x);
            }
            return Jenkins.get().getACL();
        }

        @Override public void checkAbortPermission() {
            checkPermission(Item.CANCEL);
        }

        @Override public boolean hasAbortPermission() {
            return hasPermission(Item.CANCEL);
        }

        /**
         * @deprecated use {@link #getOwnerExecutable} (which does not require a dependency on this plugin) if your core dep is 2.389+
         */
        @Deprecated
        public @CheckForNull Run<?,?> run() {
            try {
                if (!context.isReady()) {
                    return null;
                }
                return context.get(Run.class);
            } catch (Exception x) {
                LOGGER.log(FINE, "broken " + cookie + " in " + runId, x);
                finish(cookie); // probably broken, so just shut it down
                return null;
            }
        }

        /**
         * @deprecated use {@link #getOwnerExecutable} (which does not require a dependency on this plugin) if your core dep is 2.389+
         */
        @Deprecated
        public @CheckForNull Run<?,?> runForDisplay() {
            Run<?,?> r = run();
            if (r == null && /* not stored prior to 1.13 */runId != null) {
                try (ACLContext ctx = ACL.as2(ACL.SYSTEM2)) {
                    return Run.fromExternalizableId(runId);
                } catch (AccessDeniedException x) {
                    return null;
                }
            }
            return r;
        }

        // TODO 2.389+ @Override
        public @CheckForNull Queue.Executable getOwnerExecutable() {
            Run<?, ?> r = runForDisplay();
            return r instanceof Queue.Executable ? (Queue.Executable) r : null;
        }

        @Exported
        @Override public String getUrl() {
            // TODO ideally this would be found via FlowExecution.owner.executable, but how do we check for something with a URL? There is no marker interface for it: JENKINS-26091
            Run<?,?> r = runForDisplay();
            return r != null ? r.getUrl() : "";
        }

        @Override public String getDisplayName() {
            // TODO more generic to check whether FlowExecution.owner.executable is a ModelObject
            Run<?,?> r = runForDisplay();
            if (r != null) {
                String runDisplayName = r.getFullDisplayName();
                String enclosingLabel = getEnclosingLabel();
                if (enclosingLabel != null) {
                    return Messages.ExecutorStepExecution_PlaceholderTask_displayName_label(runDisplayName, enclosingLabel);
                } else {
                    return Messages.ExecutorStepExecution_PlaceholderTask_displayName(runDisplayName);
                }
            } else {
                return Messages.ExecutorStepExecution_PlaceholderTask_displayName(runId);
            }
        }

        @Exported
        @Override public String getName() {
            return getDisplayName();
        }

        @Exported
        @Override public String getFullDisplayName() {
            return getDisplayName();
        }

        static String findLabelName(FlowNode flowNode){
            LabelAction la = flowNode.getPersistentAction(LabelAction.class);

            if (la != null) {
                return la.getDisplayName();
            }
            return null;
        }

        /**
         * Similar to {@link #getEnclosingLabel()}.
         * However instead of returning the innermost label including labels inside node blocks this one
         * concatenates all labels found outside the current (node) block
         *
         * As {@link FlowNode#getEnclosingBlocks()} will return the blocks sorted from inner to outer blocks
         * this method will create a string like
         * <code>#innerblock#outerblock for</code> for a script like
         * <pre>
         *     {@code
         *     parallel(outerblock: {
         *         stage('innerblock') {
         *             node {
         *                 // .. do something here
         *             }
         *         }
         *     }
         *     }
         * </pre>
         *
         * In case there's no context available or we get a timeout we'll just return <code>baseLabel</code>
         *
         * */
        private String concatenateAllEnclosingLabels(StringBuilder labelName) {
            if (!context.isReady()) {
                return labelName.toString();
            }
            FlowNode executorStepNode = null;
            try (Timeout t = Timeout.limit(100, TimeUnit.MILLISECONDS)) {
                executorStepNode = context.get(FlowNode.class);
            } catch (Exception x) {
                LOGGER.log(Level.FINE, null, x);
            }

            if (executorStepNode != null) {
                for(FlowNode node: executorStepNode.getEnclosingBlocks()) {
                    String currentLabelName = findLabelName(node);
                    if (currentLabelName != null) {
                        labelName.append("#");
                        labelName.append(currentLabelName);
                    }
                }
            }

            return labelName.toString();
        }

        /**
        * Provide unique key which will be used to prioritize the list of possible build agents to use
        * */
        @Override
        public String getAffinityKey() {
            StringBuilder ownerTaskName = new StringBuilder(getOwnerTask().getName());
            return concatenateAllEnclosingLabels(ownerTaskName);
        }

        /** hash code of list of heads */
        private transient int lastCheckedHashCode;
        private transient String lastEnclosingLabel;
        @Restricted(NoExternalUse.class) // for Jelly
        public @CheckForNull String getEnclosingLabel() {
            if (!context.isReady()) {
                return null;
            }
            FlowNode executorStepNode;
            try (Timeout t = Timeout.limit(100, TimeUnit.MILLISECONDS)) {
                executorStepNode = context.get(FlowNode.class);
            } catch (Exception x) {
                LOGGER.log(Level.FINE, null, x);
                return null;
            }
            if (executorStepNode == null) {
                return null;
            }
            List<FlowNode> heads = executorStepNode.getExecution().getCurrentHeads();
            int headsHash = heads.hashCode(); // deterministic based on IDs of those heads
            if (headsHash == lastCheckedHashCode) {
                return lastEnclosingLabel;
            } else {
                lastCheckedHashCode = headsHash;
                return lastEnclosingLabel = computeEnclosingLabel(executorStepNode, heads);
            }
        }
        private String computeEnclosingLabel(FlowNode executorStepNode, List<FlowNode> heads) {
            for (FlowNode runningNode : heads) {
                // See if this step is inside our node {} block, and track the associated label.
                boolean match = false;
                String enclosingLabel = null;
                int count = 0;
                for (FlowNode n : runningNode.iterateEnclosingBlocks()) {
                    if (enclosingLabel == null) {
                        ThreadNameAction tna = n.getPersistentAction(ThreadNameAction.class);
                        if (tna != null) {
                            enclosingLabel = tna.getThreadName();
                        } else {
                            LabelAction a = n.getPersistentAction(LabelAction.class);
                            if (a != null) {
                                enclosingLabel = a.getDisplayName();
                            }
                        }
                        if (match && enclosingLabel != null) {
                            return enclosingLabel;
                        }
                    }
                    if (n.equals(executorStepNode)) {
                        if (enclosingLabel != null) {
                            return enclosingLabel;
                        }
                        match = true;
                    }
                    if (count++ > 100) {
                        break; // not important enough to bother
                    }
                }
            }
            return null;
        }

        @Override public long getEstimatedDuration() {
            Run<?,?> r = runForDisplay();
            // Not accurate if there are multiple agents in one build, but better than nothing:
            return r != null ? r.getEstimatedDuration() : -1;
        }

        @Override public ResourceList getResourceList() {
            return new ResourceList();
        }

        @NonNull
        @Override public Authentication getDefaultAuthentication() {
            return ACL.SYSTEM;
        }

        @NonNull
        @Override public Authentication getDefaultAuthentication(Queue.Item item) {
            return getDefaultAuthentication();
        }

        @Restricted(NoExternalUse.class)
        @Extension(ordinal=959) public static class AuthenticationFromBuild extends QueueItemAuthenticatorProvider {
            @NonNull
            @Override public List<QueueItemAuthenticator> getAuthenticators() {
                return Collections.singletonList(new QueueItemAuthenticator() {
                    @Override public Authentication authenticate(Queue.Task task) {
                        if (task instanceof PlaceholderTask) {
                            String auth = ((PlaceholderTask) task).auth;
                            LOGGER.finer(() -> "authenticating " + task);
                            if (Jenkins.ANONYMOUS.getName().equals(auth)) {
                                return Jenkins.ANONYMOUS;
                            } else if (auth != null) {
                                User user = User.getById(auth, false);
                                return user != null ? user.impersonate() : Jenkins.ANONYMOUS;
                            }
                        }
                        return null;
                    }
                });
            }
        }

        @Override public boolean isContinued() {
            return cookie != null; // in which case this is after a restart and we still claim the executor
        }

        @Override public String toString() {
            return "ExecutorStepExecution.PlaceholderTask{runId=" + runId + ",label=" + label + ",context=" + context + ",cookie=" + cookie + ",auth=" + auth + '}';
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 89 * hash + Objects.hashCode(this.context);
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            } else if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            final PlaceholderTask other = (PlaceholderTask) obj;
            return this.context.equals(other.context);
        }

        private static void finish(@CheckForNull final String cookie) {
            if (cookie == null) {
                return;
            }
            RunningTasks.get().withRunningTasks(runningTasks -> {
                final RunningTask runningTask = runningTasks.remove(cookie);
                if (runningTask == null) {
                    LOGGER.log(FINE, "no running task corresponds to {0}", cookie);
                    return;
                }
                final AsynchronousExecution execution = runningTask.execution;
                if (execution == null) {
                    // JENKINS-30759: finished before asynch execution was even scheduled
                    return;
                }
                assert runningTask.launcher != null;
                Timer.get().submit(() -> execution.completed(null)); // JENKINS-31614
                Computer.threadPoolForRemoting.submit(() -> { // JENKINS-34542, JENKINS-45553
                    try {
                        runningTask.launcher.kill(Collections.singletonMap(COOKIE_VAR, cookie));
                    } catch (ChannelClosedException x) {
                        // fine, Jenkins was shutting down
                    } catch (RequestAbortedException x) {
                        // agent was exiting; too late to kill subprocesses
                    } catch (Exception x) {
                        LOGGER.log(Level.WARNING, "failed to shut down " + cookie, x);
                    }
                });
            });
        }

        /**
         * Called when the body closure is complete.
         */
        @SuppressFBWarnings(value="SE_BAD_FIELD", justification="lease is pickled")
        private static final class Callback extends BodyExecutionCallback.TailCall {

            private static final long serialVersionUID = -1357584128994454363L;

            private final String cookie;
            @Deprecated
            private WorkspaceList.Lease lease;
            private final ExecutorStepExecution execution;

            Callback(String cookie, ExecutorStepExecution execution) {
                this.cookie = cookie;
                this.execution = execution;
            }

            @Override protected void finished(StepContext context) throws Exception {
                LOGGER.log(FINE, "finished {0}", cookie);
                try {
                    if (execution != null) {
                        WorkspaceList.Lease _lease = ExtensionList.lookupSingleton(ExecutorStepDynamicContext.WorkspaceListLeaseTranslator.class).get(execution.state);
                        if (_lease != null) {
                            _lease.release();
                        }
                    } else {
                        lease.release();
                        lease = null;
                    }
                } finally {
                    finish(cookie);
                }
                if (execution != null) {
                    execution.body = null;
                    boolean _stopping = execution.state.task.stopping;
                    execution.state.task.stopping = true;
                    try {
                        Queue.getInstance().cancel(execution.state.task);
                    } finally {
                        execution.state.task.stopping = _stopping;
                    }
                    execution.state = null;
                    context.saveState();
                }
            }

        }

        /**
         * Occupies {@link Executor} while workflow uses this build agent.
         */
        @ExportedBean
        @Restricted(NoExternalUse.class) // Class must be public for Jelly.
        public final class PlaceholderExecutable implements ContinuableExecutable, AccessControlled {

            @Override public void run() {
                TaskListener listener = null;
                Launcher launcher;
                final Run<?, ?> r;
                Computer computer = null;
                try {
                    Executor exec = Executor.currentExecutor();
                    if (exec == null) {
                        throw new IllegalStateException("running task without associated executor thread");
                    }
                    computer = exec.getOwner();
                    // Set up context for other steps inside this one.
                    Node node = computer.getNode();
                    if (node == null) {
                        throw new IllegalStateException("running computer lacks a node");
                    }
                    listener = context.get(TaskListener.class);
                    launcher = node.createLauncher(listener);
                    r = context.get(Run.class);
                    if (cookie == null) {
                        // First time around.
                        cookie = UUID.randomUUID().toString();
                        // Switches the label to a self-label, so if the executable is killed and restarted, it will run on the same node:
                        label = computer.getName();

                        EnvVars env = computer.getEnvironment();
                        env.overrideExpandingAll(computer.buildEnvironment(listener));
                        env.put(COOKIE_VAR, cookie);
                        // Cf. CoreEnvironmentContributor:
                        if (exec.getOwner() instanceof MasterComputer) {
                            env.put("NODE_NAME", node.getSelfLabel().getName()); // mirror https://github.com/jenkinsci/jenkins/blob/89d334145d2755f74f82aad07b5df4119d7fa6ce/core/src/main/java/jenkins/model/CoreEnvironmentContributor.java#L63
                        } else {
                            env.put("NODE_NAME", label);
                        }
                        env.put("EXECUTOR_NUMBER", String.valueOf(exec.getNumber()));
                        env.put("NODE_LABELS", node.getAssignedLabels().stream().map(Object::toString).collect(Collectors.joining(" ")));

                        RunningTasks.get().withRunningTasks(runningTasks -> {
                            runningTasks.put(cookie, new RunningTask());
                        });
                        // For convenience, automatically allocate a workspace, like WorkspaceStep would:
                        Job<?,?> j = r.getParent();
                        if (!(j instanceof TopLevelItem)) {
                            throw new Exception(j + " must be a top-level job");
                        }
                        FilePath p = node.getWorkspaceFor((TopLevelItem) j);
                        if (p == null) {
                            throw new IllegalStateException(node + " is offline");
                        }
                        WorkspaceList.Lease lease = computer.getWorkspaceList().allocate(p);
                        FilePath workspace = lease.path;
                        // Cf. AbstractBuild.getEnvironment:
                        env.put("WORKSPACE", workspace.getRemote());
                        final FilePath tempDir = WorkspaceList.tempDir(workspace);
                        if (tempDir != null) {
                            env.put("WORKSPACE_TMP", tempDir.getRemote()); // JENKINS-60634
                        }
                        FlowNode flowNode = context.get(FlowNode.class);
                        flowNode.addAction(new WorkspaceActionImpl(workspace, flowNode));
                        listener.getLogger().println("Running on " + ModelHyperlinkNote.encodeTo(node) + " in " + workspace);
                        ExecutorStepDynamicContext state = new ExecutorStepDynamicContext(PlaceholderTask.this, lease, exec, FilePathDynamicContext.depthOf(flowNode));
                        withExecution(execution -> {
                            execution.state = state;
                            execution.body = context.newBodyInvoker()
                                .withContexts(env, state)
                                .withCallback(new Callback(cookie, execution))
                                .start();
                            LOGGER.fine(() -> "started " + cookie + " in " + runId);
                            context.saveState();
                        });
                    } else {
                        // just rescheduled after a restart; wait for task to complete
                        LOGGER.fine(() -> "resuming " + cookie + " in " + runId);
                    }
                } catch (Exception x) {
                    if (computer != null) {
                        for (Computer.TerminationRequest tr : computer.getTerminatedBy()) {
                            x.addSuppressed(tr);
                        }
                        if (listener != null) {
                            OfflineCause oc = computer.getOfflineCause();
                            if (oc != null) {
                                listener.getLogger().println(computer.getDisplayName() + " was marked offline: " + oc);
                            }
                        }
                    }
                    context.onFailure(x);
                    return;
                }
                // wait until the invokeBodyLater call above completes and notifies our Callback object
                final TaskListener _listener = listener;
                RunningTasks.get().withRunningTasks(runningTasks -> {
                    LOGGER.fine(() -> "waiting on " + cookie + " in " + runId);
                    RunningTask runningTask = runningTasks.get(cookie);
                    if (runningTask == null) {
                        LOGGER.fine(() -> "running task apparently finished quickly for " + cookie + " in " + runId);
                        return;
                    }
                    assert runningTask.execution == null;
                    assert runningTask.launcher == null;
                    runningTask.launcher = launcher;
                    runningTask.execution = new AsynchronousExecution() {
                        @Override public void interrupt(boolean forShutdown) {
                            if (forShutdown) {
                                return;
                            }
                            LOGGER.fine(() -> "interrupted " + cookie + " in " + runId);
                            Timer.get().submit(() -> { // JENKINS-46738
                                Executor thisExecutor = /* AsynchronousExecution. */ getExecutor();
                                AtomicReference<Boolean> cancelledBodyExecution = new AtomicReference(false);
                                withExecution(execution -> {
                                    BodyExecution body = execution.body;
                                    if (body != null) {
                                        body.cancel(thisExecutor != null ? thisExecutor.getCausesOfInterruption().toArray(new CauseOfInterruption[0]) : new CauseOfInterruption[0]);
                                        cancelledBodyExecution.set(true);
                                    }
                                });
                                if (!cancelledBodyExecution.get()) { // anomalous state; perhaps build already aborted but this was left behind; let user manually cancel executor slot
                                    if (thisExecutor != null) {
                                        thisExecutor.recordCauseOfInterruption(r, _listener);
                                    }
                                    completed(null);
                                }
                            });
                        }
                        @Override public boolean blocksRestart() {
                            return false;
                        }
                        @Override public boolean displayCell() {
                            return true;
                        }
                    };
                    throw runningTask.execution;
                });
            }

            @NonNull
            @Override public PlaceholderTask getParent() {
                return PlaceholderTask.this;
            }

            @Override public Queue.Executable getParentExecutable() {
                return getOwnerExecutable();
            }

            @Exported
            public Integer getNumber() {
                Run<?, ?> r = getParent().runForDisplay();
                return r != null ? r.getNumber() : null;
            }

            @Exported
            public String getFullDisplayName() {
                return getParent().getFullDisplayName();
            }

            @Exported
            public String getDisplayName() {
                return getParent().getDisplayName();
            }

            @Exported
            @Override public long getEstimatedDuration() {
                return getParent().getEstimatedDuration();
            }

            @Exported
            public Long getTimestamp() {
                Run<?, ?> r = getParent().runForDisplay();
                return r != null ? r.getStartTimeInMillis() : null;
            }

            @Override public boolean willContinue() {
                return RunningTasks.get().withRunningTasks(runningTasks -> {
                    return runningTasks.containsKey(cookie);
                });
            }

            @Restricted(DoNotUse.class) // for Jelly
            public @CheckForNull Executor getExecutor() {
                return Executor.of(this);
            }

            @Restricted(NoExternalUse.class) // for Jelly and toString
            public String getUrl() {
                return PlaceholderTask.this.getUrl(); // we hope this has a console.jelly
            }

            @Exported(name="url")
            public String getAbsoluteUrl() {
                Run<?,?> r = runForDisplay();
                if (r == null) {
                    return "";
                }
                Jenkins j = Jenkins.getInstanceOrNull();
                String base = "";
                if (j != null) {
                    base = Util.removeTrailingSlash(j.getRootUrl()) + "/";
                }
                return base + r.getUrl();
            }

            @Override public String toString() {
                return "PlaceholderExecutable:" + PlaceholderTask.this;
            }

            private static final long serialVersionUID = 1L;

            @NonNull
            @Override
            public ACL getACL() {
                return getParent().getACL();
            }

            @Override
            public void checkPermission(@NonNull Permission permission) throws AccessDeniedException {
                getACL().checkPermission(permission);
            }

            @Override
            public boolean hasPermission(@NonNull Permission permission) {
                return getACL().hasPermission(permission);
            }
        }

        private static final long serialVersionUID = 1098885580375315588L; // as of 2.12
    }

    private static final class QueueItemActionImpl extends QueueItemAction {
        /**
         * Used to identify the task in the queue, so that its status can be identified.
         */
        private long id;

        QueueItemActionImpl(long id) {
            this.id = id;
        }

        @Override
        @CheckForNull
        public Queue.Item itemInQueue() {
            return Queue.getInstance().getItem(id);
        }
    }

    @Extension
    public static class RunningTasks {
        /** keys are {@link PlaceholderTask#cookie}s */
        private final Map<String, RunningTask> runningTasks = new HashMap<>();

        synchronized <T> T withRunningTasks(Function<Map<String, RunningTask>, T> fn) {
            return fn.apply(runningTasks);
        }

        synchronized void withRunningTasks(Consumer<Map<String, RunningTask>> fn) {
            fn.accept(runningTasks);
        }

        static RunningTasks get() {
            return ExtensionList.lookupSingleton(RunningTasks.class);
        }
    }

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(ExecutorStepExecution.class.getName());
}
