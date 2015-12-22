package de.pitkley.jenkins.plugins.dockerswarmslave;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Queue;

import java.util.List;

@Extension
public class DockerSwarmSlaveQueueDecisionHandler extends Queue.QueueDecisionHandler {
    @Override
    public boolean shouldSchedule(Queue.Task p, List<Action> actions) {
        actions.add(new DockerSwarmSlaveLabelAssignment());
        return true;
    }
}
