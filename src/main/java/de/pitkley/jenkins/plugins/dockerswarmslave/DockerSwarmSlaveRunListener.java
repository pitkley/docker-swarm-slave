package de.pitkley.jenkins.plugins.dockerswarmslave;

import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildableItemWithBuildWrappers;
import hudson.model.Run;
import hudson.model.listeners.RunListener;

@Extension
public class DockerSwarmSlaveRunListener extends RunListener<Run<?, ?>> {
    @Override
    public void onFinalized(Run<?, ?> run) {
        // Check that we have a build
        if (!AbstractBuild.class.isAssignableFrom(run.getClass())) {
            return;
        }
        AbstractBuild<?, ?> b = (AbstractBuild) run;

        // Check that it is a project
        if (!AbstractProject.class.isAssignableFrom(b.getProject().getClass())) {
            return;
        }
        AbstractProject<?, ?> project = b.getProject();

        if (!BuildableItemWithBuildWrappers.class.isAssignableFrom(project.getClass())) {
            return;
        }

        // Was our build-wrapper active?
        DockerSwarmSlaveBuildWrapper buildWrapper = DockerSwarmSlaveLabelAssignment.getDockerSwarmSlaveBuildWrapper((BuildableItemWithBuildWrappers) project);
        if (buildWrapper == null) {
            return;
        }

        // Do we have a matching DockerSwarmSlave?
        DockerSwarmSlave dockerSwarmSlave = DockerSwarmSlave.get(project);
        if (dockerSwarmSlave == null) {
            return;
        }

        // Clean everything up
        dockerSwarmSlave.cleanup();
    }
}
