package de.pitkley.jenkins.plugins.dockerswarmslave;

import hudson.model.AbstractProject;
import hudson.model.BuildableItemWithBuildWrappers;
import hudson.model.Label;
import hudson.model.labels.LabelAssignmentAction;
import hudson.model.queue.SubTask;

import java.util.logging.Level;
import java.util.logging.Logger;

public class DockerSwarmSlaveLabelAssignment implements LabelAssignmentAction {

    private transient final Logger logger = Logger.getLogger(getClass().getName());

    @Override
    public Label getAssignedLabel(SubTask subTask) {
        // Check class constraints
        if (!BuildableItemWithBuildWrappers.class.isAssignableFrom(subTask.getClass())) {
            return subTask.getAssignedLabel();
        }

        // Get build wrapper
        DockerSwarmSlaveBuildWrapper buildWrapper = getDockerSwarmSlaveBuildWrapper((BuildableItemWithBuildWrappers) subTask);

        // Check if the BuildWrapper exists
        if (buildWrapper == null) {
            return subTask.getAssignedLabel();
        }

        AbstractProject<?, ?> project = ((BuildableItemWithBuildWrappers) subTask).asProject();

        // Check if the build should be aborted
        if (DockerSwarmSlaveAbortHelper.shouldAbortBuild(project)) {
            return subTask.getAssignedLabel();
        }

        try {
            DockerSwarmSlave dockerSwarmSlave = DockerSwarmSlave.get(project);
            if (dockerSwarmSlave == null) {
                // Create the docker-swarm-slave
                dockerSwarmSlave = DockerSwarmSlave.create(buildWrapper, project, project.getNextBuildNumber());
                dockerSwarmSlave.createSlave();
            }

            // Since `getAssignedLabel(SubTask)` is called repeatedly if there is no build-processor with the given
            // label available, we have to timeout at some point if the started docker-container doesn't come up.
            if (dockerSwarmSlave.shouldTimeout()) {
                throw new RuntimeException("Docker container (or Docker itself) didn't respond in time, aborting.");
            }

            String slaveLabel = dockerSwarmSlave.getSlaveLabel();
            return Label.get(slaveLabel);
        } catch (Exception e) {
            // Log the exception
            logger.log(Level.SEVERE, e.getMessage(), e);
            // Abort the build
            // (aborting the build is enough to get the docker-container cleaned up, see `DockerSwarmSlaveRunListener#onFinalized(R)`
            DockerSwarmSlaveAbortHelper.abortBuild(project, e);
        }

        return subTask.getAssignedLabel();
    }

    /**
     * This method checks current jobs configuration to see if the DockerSwarmSlaveBuildWrapper
     * is enabled. It does this by checking if DockerSwarmSlaveBuildWrapper exists in the jobs
     * BuildWrappersList.
     * <p/>
     * Source: https://github.com/jenkinsci/job-node-stalker-plugin
     *
     * @param project the job that is currently running
     * @return <ul>
     * <li>Returns null if the projects BuildWrapperList does not exist</li>
     * <li>Returns DockerSwarmSlaveBuildWrapper if the projects BuildWrapperList is found and contains the build wrapper</li>
     * </ul>
     */
    protected static DockerSwarmSlaveBuildWrapper getDockerSwarmSlaveBuildWrapper(BuildableItemWithBuildWrappers project) {
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
