package de.pitkley.jenkins.plugins.dockerswarmslave;

import hudson.model.AbstractProject;

import java.util.HashMap;
import java.util.Map;

public class DockerSwarmSlaveAbortHelper {

    private static final Map<AbstractProject<?, ?>, Exception> MAP = new HashMap<AbstractProject<?, ?>, Exception>();

    public static void abortBuild(AbstractProject<?, ?> project, Exception e) {
        if (MAP.containsKey(project)) {
            return;
        }
        MAP.put(project, e);
    }

    public static boolean shouldAbortBuild(AbstractProject<?, ?> project) {
        return MAP.containsKey(project);
    }

    public static Exception getAbortBuildCause(AbstractProject<?, ?> project) {
        return MAP.get(project);
    }

    public static Exception remove(AbstractProject<?, ?> project) {
        return MAP.remove(project);
    }

}
