package com.processdesk.kogito;

import org.jbpm.bpmn2.xml.BPMNDISemanticModule;
import org.jbpm.bpmn2.xml.BPMNExtensionsSemanticModule;
import org.jbpm.bpmn2.xml.BPMNSemanticModule;
import org.jbpm.compiler.xml.XmlProcessReader;
import org.jbpm.compiler.xml.core.SemanticModules;
import org.kie.api.definition.process.Process;
import org.kie.kogito.internal.process.workitem.KogitoWorkItem;
import org.kie.kogito.internal.process.workitem.KogitoWorkItemHandler;
import org.kie.kogito.internal.process.workitem.KogitoWorkItemManager;
import org.kie.kogito.internal.process.workitem.WorkItemTransition;
import org.kie.kogito.Addons;
import org.kie.kogito.StaticApplication;
import org.kie.kogito.StaticConfig;
import org.kie.kogito.process.ProcessInstance;
import org.kie.kogito.process.WorkItemHandlerConfig;
import org.kie.kogito.process.bpmn2.BpmnProcess;
import org.kie.kogito.process.bpmn2.BpmnVariables;
import org.kie.api.event.process.ProcessNodeTriggeredEvent;
import org.kie.kogito.internal.process.event.DefaultKogitoProcessEventListener;
import org.kie.kogito.process.impl.CachedProcessEventListenerConfig;
import org.kie.kogito.process.impl.CachedWorkItemHandlerConfig;
import org.kie.kogito.process.impl.StaticProcessConfig;
import org.kie.kogito.process.workitems.impl.DefaultKogitoWorkItemHandler;
import org.kie.kogito.services.uow.CollectingUnitOfWorkFactory;
import org.kie.kogito.services.uow.DefaultUnitOfWorkManager;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.List;
import java.util.Map;

/**
 * Runs BPMN processes on the Kogito process engine.
 *
 * <p>Process definitions are read at runtime rather than generated at build time,
 * which is what lets a file the user just edited be executed immediately. The engine
 * doing the executing is Kogito's own, so behaviour matches a deployed Kogito service.
 */
public class ProcessRuntime {

    public record ProcessOutcome(String processId, String processName, String status,
                                 Map<String, Object> variables, String error, List<String> path) {}

    /** Definitions in a BPMN file, without running anything. */
    public List<Process> read(String bpmnXml) {
        try {
            SemanticModules modules = new SemanticModules();
            modules.addSemanticModule(new BPMNSemanticModule());
            modules.addSemanticModule(new BPMNDISemanticModule());
            modules.addSemanticModule(new BPMNExtensionsSemanticModule());
            XmlProcessReader reader = new XmlProcessReader(modules, Thread.currentThread().getContextClassLoader());
            List<Process> processes = reader.read(new StringReader(bpmnXml));
            if (processes == null || processes.isEmpty()) {
                throw new IllegalArgumentException("This file doesn't contain a process.");
            }
            return processes;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Kogito couldn't read this process: " + e.getMessage(), e);
        }
    }

    /**
     * Starts one instance of the first process in the file and runs it as far as it
     * goes without external input.
     */
    public ProcessOutcome start(String bpmnXml, Map<String, Object> variables) {
        Process definition = read(bpmnXml).get(0);

        // A minimal in-memory Kogito application: no persistence, no add-ons. The process
        // config has to be reachable through the application's config as well as passed
        // directly, because the engine looks it up both ways while activating.
        PathRecorder path = new PathRecorder(stepNamesOf(bpmnXml));
        StaticProcessConfig processConfig = new StaticProcessConfig(
                previewWorkItemHandlers(),
                new CachedProcessEventListenerConfig(List.of(path)),
                new DefaultUnitOfWorkManager(new CollectingUnitOfWorkFactory()));
        StaticApplication application =
                new StaticApplication(new StaticConfig(Addons.EMTPY, processConfig));

        BpmnProcess process = new BpmnProcess(definition, processConfig, application);
        process.configure();
        process.activate();

        ProcessInstance<BpmnVariables> instance =
                process.createInstance(BpmnVariables.create(variables));
        instance.start();

        String error = instance.error()
                .map(e -> e.errorMessage() + " (at node " + e.failedNodeId() + ")")
                .orElse(null);

        return new ProcessOutcome(
                definition.getId(),
                definition.getName(),
                describe(instance.status()),
                instance.variables().toMap(),
                error,
                path.steps());
    }

    private static Set<String> stepNamesOf(String bpmnXml) {
        try {
            return new LinkedHashSet<>(com.processdesk.harness.BpmnDocument.parse(bpmnXml).stepNames());
        } catch (Exception e) {
            return Set.of();
        }
    }

    /**
     * Records the steps the engine actually entered, in order.
     *
     * <p>This is the run's own account of itself, not a re-reading of the file: with a
     * gateway, which branch was taken is only knowable by watching it happen.
     */
    private static final class PathRecorder extends DefaultKogitoProcessEventListener {

        private final Set<String> stepNames;
        private final List<String> steps = new ArrayList<>();

        PathRecorder(Set<String> stepNames) {
            this.stepNames = stepNames;
        }

        @Override
        public void beforeNodeTriggered(ProcessNodeTriggeredEvent event) {
            // "before" is entry order. The matching "after" event fires once a node's
            // subtree is done, which walks out of the process backwards.
            String name = event.getNodeInstance().getNodeName();
            // Only steps: gateways and events are how the process is wired, not places
            // work visibly happened, and a loop can re-enter a step it already passed.
            if (name != null && stepNames.contains(name) && !steps.contains(name)) {
                steps.add(name);
            }
        }

        List<String> steps() {
            return List.copyOf(steps);
        }
    }

    /**
     * Handlers used for preview runs.
     *
     * <p>A deployed service registers handlers that do the real work — call a system,
     * assign a human task. Here every work item completes immediately, so a preview run
     * shows the path work takes through the process rather than performing it. Anything
     * that depends on a handler's output will not be realistic.
     */
    private WorkItemHandlerConfig previewWorkItemHandlers() {
        return new PreviewWorkItemHandlerConfig();
    }

    /**
     * Supplies a no-op handler for every work item type, including the empty type a
     * plain BPMN task declares. Without the fallback, a process stops with an error on
     * the first step whose type has no handler registered, which for a file the user is
     * still designing is most of them.
     */
    private static final class PreviewWorkItemHandlerConfig extends CachedWorkItemHandlerConfig {

        /** "" is the type of a plain BPMN task, which is what an unconfigured step is. */
        private static final List<String> PREVIEWED_TYPES =
                List.of("", "Task", "Human Task", "Service Task", "Rest", "Milestone");

        private final KogitoWorkItemHandler fallback = new CompleteImmediatelyHandler();

        PreviewWorkItemHandlerConfig() {
            // The engine registers handlers by walking names(), so every type that should
            // run has to be named here, not only resolved on lookup.
            PREVIEWED_TYPES.forEach(type -> register(type, new CompleteImmediatelyHandler()));
        }

        @Override
        public KogitoWorkItemHandler forName(String name) {
            try {
                KogitoWorkItemHandler handler = super.forName(name);
                return handler != null ? handler : fallback;
            } catch (RuntimeException e) {
                return fallback;
            }
        }
    }

    /**
     * Completes each work item as soon as the engine reaches it, so a preview run
     * walks the whole process instead of stopping at the first step that would wait
     * for a person or an external system.
     */
    private static final class CompleteImmediatelyHandler extends DefaultKogitoWorkItemHandler {

        @Override
        public Optional<WorkItemTransition> activateWorkItemHandler(KogitoWorkItemManager manager,
                KogitoWorkItemHandler handler, KogitoWorkItem workItem, WorkItemTransition transition) {
            return Optional.of(workItemLifeCycle.newTransition(
                    "complete", workItem.getPhaseStatus(), workItem.getResults()));
        }
    }

    /** Kogito's numeric instance states, in the vocabulary the UI uses. */
    private static String describe(int status) {
        return switch (status) {
            case ProcessInstance.STATE_PENDING -> "pending";
            case ProcessInstance.STATE_ACTIVE -> "running";
            case ProcessInstance.STATE_COMPLETED -> "completed";
            case ProcessInstance.STATE_ABORTED -> "aborted";
            case ProcessInstance.STATE_SUSPENDED -> "suspended";
            case ProcessInstance.STATE_ERROR -> "error";
            default -> "unknown";
        };
    }
}
