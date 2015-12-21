package de.pitkley.jenkins.plugins.dockerswarmslave;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Queue;

import java.util.List;
import java.util.logging.Logger;

@Extension
public class DockerSwarmSlaveQueueDecisionHandler extends Queue.QueueDecisionHandler {
    @Override
    public boolean shouldSchedule(Queue.Task p, List<Action> actions) {
        Logger.getLogger(this.getClass().getName()).info("in shouldSchedule");
        actions.add(new DockerSwarmSlaveLabelAssignment());
        return true;
    }
}
