/*******************************************************************************
 * Copyright (c) 2008, 2013 Sonatype Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Sonatype Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.tycho.core.osgitools;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.eclipse.tycho.BuildOutputDirectory;
import org.eclipse.tycho.ReactorProject;
import org.eclipse.tycho.ReactorProjectIdentities;
import org.eclipse.tycho.core.TychoConstants;
import org.eclipse.tycho.osgi.adapters.MavenReactorProjectIdentities;
import org.osgi.framework.Version;

public class DefaultReactorProject implements ReactorProject {
    private final MavenProject project;

    public DefaultReactorProject(MavenProject project) {
        if (project == null) {
            throw new NullPointerException();
        }

        this.project = project;
    }

    public static ReactorProject adapt(MavenProject project) {
        if (project == null) {
            return null;
        }

        ReactorProject reactorProject = (ReactorProject) project.getContextValue(CTX_REACTOR_PROJECT);
        if (reactorProject == null) {
            reactorProject = new DefaultReactorProject(project);
            project.setContextValue(CTX_REACTOR_PROJECT, reactorProject);
        }
        return reactorProject;
    }

    public static List<ReactorProject> adapt(MavenSession session) {
        ArrayList<ReactorProject> result = new ArrayList<ReactorProject>();
        for (MavenProject project : session.getProjects()) {
            result.add(adapt(project));
        }
        return result;
    }

    public boolean sameProject(Object otherProject) {
        return project.equals(otherProject);
    }

    public File getBasedir() {
        return project.getBasedir();
    }

    public String getPackaging() {
        return project.getPackaging();
    }

    public String getGroupId() {
        return project.getGroupId();
    }

    public String getArtifactId() {
        return project.getArtifactId();
    }

    public String getVersion() {
        return project.getVersion();
    }

    public ReactorProjectIdentities getIdentities() {
        return new MavenReactorProjectIdentities(project);
    }

    public File getOutputDirectory() {
        return new File(project.getBuild().getOutputDirectory());
    }

    public BuildOutputDirectory getBuildDirectory() {
        return new BuildOutputDirectory(project.getBuild().getDirectory());
    }

    public File getTestOutputDirectory() {
        return new File(project.getBuild().getTestOutputDirectory());
    }

    public File getArtifact() {
        Artifact artifact = project.getArtifact();
        return artifact != null ? artifact.getFile() : null;
    }

    public File getArtifact(String artifactClassifier) {
        Artifact artifact = null;
        if (artifactClassifier == null) {
            artifact = project.getArtifact();
        } else {
            for (Artifact attached : project.getAttachedArtifacts()) {
                if (artifactClassifier.equals(attached.getClassifier())) {
                    artifact = attached;
                    break;
                }
            }
        }
        return artifact != null ? artifact.getFile() : null;
    }

    public Object getContextValue(String key) {
        return project.getContextValue(key);
    }

    public void setContextValue(String key, Object value) {
        project.setContextValue(key, value);
    }

    public void setDependencyMetadata(boolean primary, Set<?> installableUnits) {
        project.setContextValue(getDependencyMetadataKey(primary), installableUnits);
    }

    public Set<?> getDependencyMetadata() {
        Set<?> primary = getDependencyMetadata(true);
        Set<?> secondary = getDependencyMetadata(false);

        if (primary == null) {
            return secondary;
        } else if (secondary == null) {
            return primary;
        }

        LinkedHashSet<Object> result = new LinkedHashSet<Object>(primary);
        result.addAll(secondary);
        return result;
    }

    public Set<?> getDependencyMetadata(boolean primary) {
        Set<?> metadata = (Set<?>) project.getContextValue(getDependencyMetadataKey(primary));
        return metadata;
    }

    private static String getDependencyMetadataKey(boolean primary) {
        return primary ? CTX_DEPENDENCY_METADATA : CTX_SECONDARY_DEPENDENCY_METADATA;
    }

    public String getExpandedVersion() {
        String version = (String) project.getContextValue(TychoConstants.CTX_EXPANDED_VERSION);
        if (version != null) {
            return version;
        }

        throw new IllegalStateException("Project " + getId() + " does not have expanded version");
    }

    public void setExpandedVersion(String originalVersion, String qualifier) {
        Version version = Version.parseVersion(originalVersion);

        String expandedVersion = new Version(version.getMajor(), version.getMinor(), version.getMicro(), qualifier)
                .toString();

        String oldVersion = (String) project.getContextValue(TychoConstants.CTX_EXPANDED_VERSION);

        if (oldVersion != null && !oldVersion.equals(expandedVersion)) {
            throw new IllegalStateException("Cannot redefine expanded version");
        }

        project.setContextValue(TychoConstants.CTX_EXPANDED_VERSION, expandedVersion);
    }

    public String getId() {
        return project.getId();
    }

    @Override
    public int hashCode() {
        return project.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof DefaultReactorProject)) {
            return false;
        }

        DefaultReactorProject other = (DefaultReactorProject) obj;

        return project.equals(other.project);
    }

    @Override
    public String toString() {
        return project.toString();
    }
}
