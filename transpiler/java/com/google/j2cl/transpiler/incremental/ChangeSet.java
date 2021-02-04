package com.google.j2cl.transpiler.incremental;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ChangeSet {
    private String dir;

    private List<String>        removed        = new ArrayList<>(); // files
    private Set<String>         updated        = new HashSet<>(); // files
    private Set<String>         impacted       = new HashSet<>(); // files
    private List<String>        added          = new ArrayList<>(); // files
    private List<String>        all            = new ArrayList<>(); // files

    private List<String>        sourcesToProcess;
    private Set<String>         sourcesToProcessSet;


    public ChangeSet(String dir) {
        this.dir = dir;
    }

    public String getDir() {
        return dir;
    }

    public List<String> getRemoved() {
        return removed;
    }

    public Set<String> getUpdated() {
        return updated;
    }

    public Set<String> getImpacted() {
        return impacted;
    }

    public List<String> getAdded() {
        return added;
    }

    public List<String> getAll() {
        return all;
    }

    public List<String> getSourcesToProcess() {
        if (sourcesToProcess == null) {
            sourcesToProcess = new ArrayList<>(getAdded());
            sourcesToProcess.addAll(getUpdated());
            sourcesToProcess.addAll(getImpacted());
        }

        return sourcesToProcess;
    }

    public Set<String> getSourcesToProcesSet() {
        if (sourcesToProcessSet == null) {
            sourcesToProcessSet = new HashSet<>(getSourcesToProcess());
        }
        return sourcesToProcessSet;
    }

    @Override public String toString() {
        return "ChangeSet{" +
               "removed=" + removed +
               ", updated=" + updated +
               ", impacted=" + impacted +
               ", added=" + added +
               ", all=" + all +
               '}';
    }
}
