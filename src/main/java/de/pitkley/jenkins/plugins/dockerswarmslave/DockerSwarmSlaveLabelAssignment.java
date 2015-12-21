package de.pitkley.jenkins.plugins.dockerswarmslave;

import hudson.model.BuildableItemWithBuildWrappers;
import hudson.model.Label;
import hudson.model.labels.LabelAssignmentAction;
import hudson.model.queue.SubTask;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DockerSwarmSlaveLabelAssignment implements LabelAssignmentAction {

    private transient final Logger logger = Logger.getLogger(getClass().getName());

    @Override
    public Label getAssignedLabel(SubTask subTask) {
        logger.info("[LABEL] entry, label: " + subTask.getAssignedLabel());

        // Check class constraints
        if (!BuildableItemWithBuildWrappers.class.isAssignableFrom(subTask.getClass())) {
            logger.info("[LABEL] exit, not buildwrapped");
            return subTask.getAssignedLabel();
        }

        DockerSwarmSlaveBuildWrapper buildWrapper = getDockerSwarmSlaveBuildWrapper((BuildableItemWithBuildWrappers) subTask);

        // Check if the BuildWrapper exists and build is not aborted
        if (buildWrapper == null || buildWrapper.isAborted()) {
            logger.info("[LABEL] exit, no build wrapper found or aborted, bw: " + buildWrapper);
            return subTask.getAssignedLabel();
        }

        DockerSwarmSlave dockerSwarmSlave = null;
        try {
            dockerSwarmSlave = buildWrapper.getDockerSwarmSlave();
            if (buildWrapper.getDockerSwarmSlave() == null) {
                // Create the docker-swarm-slave
                dockerSwarmSlave = DockerSwarmSlave.create(buildWrapper, ((BuildableItemWithBuildWrappers) subTask).asProject());
                dockerSwarmSlave.start();
                logger.info("[LABEL] post start");
            }

            if (dockerSwarmSlave.shouldTimeout()) {
                throw new RuntimeException("Docker container didn't respond in time, aborting.");
            }

            String label = dockerSwarmSlave.getSlaveName();
            logger.info("[LABEL] exit, setting label: " + label);
            return Label.get(label);
        } catch (Exception e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            buildWrapper.abortBuild(e);
            if (dockerSwarmSlave != null) {
                try {
                    dockerSwarmSlave.stop();
                } catch (Exception ignored) {
                }
            }
        }

        logger.info("[LABEL] exiting with assigned label: " + subTask.getAssignedLabel());
        return subTask.getAssignedLabel();
    }

    /**
     * This method checks current jobs configuration to see if the DockerSwarmSlaveBuildWrapper
     * is enabled. It does this by checking if DockerSwarmSlaveBuildWrapper exists in the jobs
     * BuildWrappersList.
     * <p>
     * Source: https://github.com/jenkinsci/job-node-stalker-plugin
     *
     * @param project the job that is currently running
     * @return <ul>
     * <li>Returns null if the projects BuildWrapperList does not exist</li>
     * <li>Returns DockerSwarmSlaveBuildWrapper if the projects BuildWrapperList is found and contains the build wrapper</li>
     * </ul>
     */
    protected DockerSwarmSlaveBuildWrapper getDockerSwarmSlaveBuildWrapper(BuildableItemWithBuildWrappers project) {
        if (project.getBuildWrappersList() == null) {
            return null;
        }

        return project.getBuildWrappersList().get(DockerSwarmSlaveBuildWrapper.class);
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return null;
    }
}
