package de.pitkley.jenkins.plugins.dockerswarmslave;

import hudson.Extension;
import hudson.model.Build;
import hudson.model.Project;
import hudson.model.Run;
import hudson.model.listeners.RunListener;

@Extension
public class DockerSwarmSlaveRunListener extends RunListener<Run<?, ?>> {
    @Override
    public void onFinalized(Run<?, ?> run) {
        // Check that we have a build
        if (!Build.class.isAssignableFrom(run.getClass())) {
            return;
        }
        Build<?, ?> b = (Build) run;

        // Check that it is a project
        if (!Project.class.isAssignableFrom(b.getProject().getClass())) {
            return;
        }
        Project<?, ?> project = b.getProject();

        // Was our build-wrapper active?
        DockerSwarmSlaveBuildWrapper buildWrapper = DockerSwarmSlaveLabelAssignment.getDockerSwarmSlaveBuildWrapper(project);
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
