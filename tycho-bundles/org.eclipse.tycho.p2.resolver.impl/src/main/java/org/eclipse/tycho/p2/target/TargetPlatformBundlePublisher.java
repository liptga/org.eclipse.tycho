/*******************************************************************************
 * Copyright (c) 2011, 2013 SAP AG and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    SAP AG - initial API and implementation
 *******************************************************************************/
package org.eclipse.tycho.p2.target;

import java.io.File;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.AssertionFailedException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.publisher.IPublisherAction;
import org.eclipse.equinox.p2.publisher.IPublisherInfo;
import org.eclipse.equinox.p2.publisher.Publisher;
import org.eclipse.equinox.p2.publisher.PublisherInfo;
import org.eclipse.equinox.p2.publisher.PublisherResult;
import org.eclipse.equinox.p2.publisher.eclipse.BundlesAction;
import org.eclipse.equinox.p2.repository.artifact.IArtifactDescriptor;
import org.eclipse.tycho.core.facade.MavenLogger;
import org.eclipse.tycho.p2.impl.publisher.MavenPropertiesAdvice;
import org.eclipse.tycho.p2.impl.publisher.repo.TransientArtifactRepository;
import org.eclipse.tycho.p2.metadata.IArtifactFacade;
import org.eclipse.tycho.p2.repository.MavenRepositoryCoordinates;
import org.eclipse.tycho.repository.local.GAVArtifactDescriptor;
import org.eclipse.tycho.repository.p2base.artifact.provider.IRawArtifactFileProvider;
import org.eclipse.tycho.repository.p2base.artifact.provider.formats.ArtifactTransferPolicies;
import org.eclipse.tycho.repository.p2base.artifact.repository.ArtifactRepositoryBaseImpl;
import org.eclipse.tycho.repository.util.StatusTool;

@SuppressWarnings("restriction")
public class TargetPlatformBundlePublisher {

    private final MavenLogger logger;
    private final PublishedBundlesArtifactRepository publishedArtifacts;
    private static final int ARTIFACT_CACHE_SIZE = 1000;
    /**
     * Cache containing {@link IArtifactDescriptor} and {@link IInstallableUnit} for a given
     * {@link IArtifactFacade}. During profiling Tyhco it turned out, that turning normal maven
     * artifacts into installable p2 artifacts takes a lot of time. Especially calculating MD5 hash
     * for an artifact. In case of a multimodule project the same artifact will be published several
     * times. This makes no sense. Since these installable artifacts are examined before the actual
     * maven build happens, it is really not likely that these maven artifacts change during the
     * whole build. So I cache them here. The actual items are only metadata, so the cache will not
     * be that big. Just to make it sure the cache is a {@link LinkedHashMap} with limited capacity.
     */
    private static final Map<IArtifactFacade, InstallableUnitCacheItem> installableUnitCache = new CacheMap();

    public TargetPlatformBundlePublisher(File localMavenRepositoryRoot, MavenLogger logger) {
        this.publishedArtifacts = new PublishedBundlesArtifactRepository(localMavenRepositoryRoot);
        this.logger = logger;
    }

    /**
     * Generate p2 data for an artifact, if the artifact is an OSGI bundle.
     * <p>
     * The p2 metadata produced by this method is only determined by the artifact, and the function
     * used for this conversion must not change (significantly) even in future versions. This is
     * required because the resulting metadata can be included in p2 repositories built by Tycho,
     * and hence may be propagated into the p2 universe. Therefore the metadata generated by this
     * method shall fulfill the basic assumption of p2 that ID+version uniquely identifies a
     * unit/artifact. Assuming that distinct bundle artifacts specify unique ID+versions in their
     * manifest (which should be mostly true), and the p2 BundlesAction used in the implementation
     * doesn't change significantly (which can also be assumed), these conditions specified above a
     * met.
     * </p>
     * <p>
     * In slight deviation on the principles described in the previous paragraph, the implementation
     * adds GAV properties to the generated IU. This is justified by the potential benefits of
     * tracing the origin of artifact.
     * </p>
     * 
     * @param mavenArtifact
     *            An artifact in local file system.
     * @return the p2 metadata of the artifact, or <code>null</code> if the artifact isn't a valid
     *         OSGi bundle.
     */
    IInstallableUnit attemptToPublishBundle(IArtifactFacade mavenArtifact) {
        IInstallableUnit publishedIU = null;
        IArtifactDescriptor publishedDescriptor = null;
        synchronized (installableUnitCache) {
            /*
             * I really don't think that Tycho likes multithreaded build. But in case if it still
             * used multithreaded just to prevent race conditions we simply serialize these
             * requests.
             */
            InstallableUnitCacheItem cacheItem = installableUnitCache.get(mavenArtifact);
            if (cacheItem == null) {
                logger.debug("No cached published artifact for " + mavenArtifact + "(" + mavenArtifact.getGroupId()
                        + ":" + mavenArtifact.getArtifactId() + ":" + mavenArtifact.getClassifier() + ":"
                        + mavenArtifact.getVersion() + ")");

                if (!isAvailableAsLocalFile(mavenArtifact)) {
                    // this should have been ensured by the caller
                    throw new IllegalArgumentException("Not an artifact file: " + mavenArtifact.getLocation());
                }
                if (isCertainlyNoBundle(mavenArtifact)) {
                    return null;
                }

                PublisherRun publisherRun = new PublisherRun(mavenArtifact);
                IStatus status = publisherRun.execute();

                if (!status.isOK()) {
                    /**
                     * If publishing of a jar fails, it is simply not added to the resolution
                     * context. The BundlesAction already ignores non-bundle JARs silently, so an
                     * error status here indicates a caught exception that we at least want to see.
                     */
                    logger.warn(StatusTool.collectProblems(status), status.getException());
                }

                publishedIU = publisherRun.getPublishedUnitIfExists();
                if (publishedIU != null) {
                    publishedDescriptor = publisherRun.getPublishedArtifactDescriptor();
                }
                installableUnitCache.put(mavenArtifact, new InstallableUnitCacheItem(publishedIU, publishedDescriptor));
            } else {
                publishedIU = cacheItem.getInstallableUnit();
                publishedDescriptor = cacheItem.getArtifactDescriptor();
            }
        }
        if (publishedIU != null) {
            publishedArtifacts.addPublishedArtifact(publishedDescriptor, mavenArtifact);
        }
        return publishedIU;
    }

    private boolean isAvailableAsLocalFile(IArtifactFacade artifact) {
        File localLocation = artifact.getLocation();
        return localLocation != null && localLocation.isFile();
    }

    private boolean isCertainlyNoBundle(IArtifactFacade artifact) {
        return !artifact.getLocation().getName().endsWith(".jar");
    }

    IRawArtifactFileProvider getArtifactRepoOfPublishedBundles() {
        return publishedArtifacts;
    }

    private static class PublisherRun {

        private static final String EXCEPTION_CONTEXT = "Error while adding Maven artifact to the target platform: ";

        private final IArtifactFacade mavenArtifact;

        private PublisherInfo publisherInfo;
        private TransientArtifactRepository collectedDescriptors;
        private PublisherResult publisherResult;

        PublisherRun(IArtifactFacade artifact) {
            this.mavenArtifact = artifact;
        }

        IStatus execute() {
            publisherInfo = new PublisherInfo();
            enableArtifactDescriptorCollection();
            enableUnitAnnotationWithGAV();

            BundlesAction bundlesAction = new BundlesAction(new File[] { mavenArtifact.getLocation() });
            IStatus status = executePublisherAction(bundlesAction);
            return status;
        }

        private void enableArtifactDescriptorCollection() {
            publisherInfo.setArtifactOptions(IPublisherInfo.A_INDEX);
            collectedDescriptors = new TransientArtifactRepository();
            publisherInfo.setArtifactRepository(collectedDescriptors);
        }

        private void enableUnitAnnotationWithGAV() {
            MavenPropertiesAdvice advice = new MavenPropertiesAdvice(mavenArtifact.getGroupId(),
                    mavenArtifact.getArtifactId(), mavenArtifact.getVersion(), mavenArtifact.getClassifier());
            publisherInfo.addAdvice(advice);
        }

        private IStatus executePublisherAction(BundlesAction action) {
            IPublisherAction[] actions = new IPublisherAction[] { action };
            publisherResult = new PublisherResult();
            return new Publisher(publisherInfo, publisherResult).publish(actions, null);
        }

        IInstallableUnit getPublishedUnitIfExists() {
            Collection<IInstallableUnit> units = publisherResult.getIUs(null, null);
            if (units.isEmpty()) {
                // the BundlesAction simply does not create any IUs if the JAR is not a bundle
                return null;
            } else if (units.size() == 1) {
                return units.iterator().next();
            } else {
                throw new AssertionFailedException(EXCEPTION_CONTEXT + "BundlesAction produced more than one IU for "
                        + mavenArtifact.getLocation());
            }
        }

        IArtifactDescriptor getPublishedArtifactDescriptor() {
            Set<IArtifactDescriptor> descriptors = collectedDescriptors.getArtifactDescriptors();
            if (descriptors.isEmpty()) {
                throw new AssertionFailedException(EXCEPTION_CONTEXT
                        + "BundlesAction did not create an artifact entry for " + mavenArtifact.getLocation());
            } else if (descriptors.size() == 1) {
                return descriptors.iterator().next();
            } else {
                throw new AssertionFailedException(EXCEPTION_CONTEXT
                        + "BundlesAction created more than one artifact entry for " + mavenArtifact.getLocation());
            }
        }
    }

    /**
     * p2 artifact repository providing the POM dependency Maven artifacts.
     * 
     * <p>
     * Although the provided artifacts are also stored in the local Maven repository, they cannot be
     * made available via the <tt>LocalArtifactRepository</tt> artifact repository implementation.
     * The reason is that there are differences is how the artifacts provided by the respective
     * implementations may be updated:
     * <ul>
     * <li>For the <tt>LocalArtifactRepository</tt> artifacts, it can be assumed that all updates
     * (e.g. as a result of a <tt>mvn install</tt>) are done by Tycho. Therefore it is safe to write
     * the p2 artifact index data to disk together with the artifacts.</li>
     * <li>For the POM dependency artifacts, this assumption does not hold true: e.g. a
     * maven-bundle-plugin build may update an artifact in the local Maven repository without
     * notifying Tycho. So if we had written p2 artifact index data to disk, that data might then be
     * stale.</li>
     * </ul>
     * To avoid the need to implement and index invalidation logic, we use this separate artifact
     * repository implementation with an in-memory index.
     * </p>
     */
    private static class PublishedBundlesArtifactRepository extends ArtifactRepositoryBaseImpl<GAVArtifactDescriptor> {

        PublishedBundlesArtifactRepository(File localMavenRepositoryRoot) {
            super(null, localMavenRepositoryRoot.toURI(), ArtifactTransferPolicies.forLocalArtifacts());
        }

        void addPublishedArtifact(IArtifactDescriptor baseDescriptor, IArtifactFacade mavenArtifact) {
            // TODO allow other extensions than the default ("jar")?
            MavenRepositoryCoordinates repositoryCoordinates = new MavenRepositoryCoordinates(
                    mavenArtifact.getGroupId(), mavenArtifact.getArtifactId(), mavenArtifact.getVersion(),
                    mavenArtifact.getClassifier(), null);

            GAVArtifactDescriptor descriptorForRepository = new GAVArtifactDescriptor(baseDescriptor,
                    repositoryCoordinates);

            File requiredArtifactLocation = new File(getBaseDir(), descriptorForRepository.getMavenCoordinates()
                    .getLocalRepositoryPath());
            File actualArtifactLocation = mavenArtifact.getLocation();
            if (!equivalentPaths(requiredArtifactLocation, actualArtifactLocation)) {
                throw new AssertionFailedException(
                        "The Maven artifact to be added to the target platform is not stored at the required location on disk: required \""
                                + requiredArtifactLocation + "\" but was \"" + actualArtifactLocation + "\"");
            }

            internalAddInternalDescriptor(descriptorForRepository);
        }

        private boolean equivalentPaths(File path, File otherPath) {
            return path.equals(otherPath);
        }

        @Override
        protected GAVArtifactDescriptor getInternalDescriptorForAdding(IArtifactDescriptor descriptor) {
            // artifacts are only added via the dedicated method
            throw new UnsupportedOperationException();
        }

        @Override
        protected IArtifactDescriptor getComparableDescriptor(IArtifactDescriptor descriptor) {
            // any descriptor can be converted to our internal type GAVArtifactDescriptor
            return toInternalDescriptor(descriptor);
        }

        private GAVArtifactDescriptor toInternalDescriptor(IArtifactDescriptor descriptor) {
            // TODO share with LocalArtifactRepository?
            if (descriptor instanceof GAVArtifactDescriptor && descriptor.getRepository() == this) {
                return (GAVArtifactDescriptor) descriptor;
            } else {
                GAVArtifactDescriptor internalDescriptor = new GAVArtifactDescriptor(descriptor);
                internalDescriptor.setRepository(this);
                return internalDescriptor;
            }
        }

        @Override
        protected File internalGetArtifactStorageLocation(IArtifactDescriptor descriptor) {
            String relativePath = toInternalDescriptor(descriptor).getMavenCoordinates().getLocalRepositoryPath();
            return new File(getBaseDir(), relativePath);
        }

        private File getBaseDir() {
            return new File(getLocation());
        }

    }

    /**
     * Simple access order {@link LinkedHashMap} based cache.
     * 
     * @author liptak
     */
    private static final class CacheMap extends LinkedHashMap<IArtifactFacade, InstallableUnitCacheItem> {
        private static final long serialVersionUID = 1L;
        private static final float LOAD_FACTOR = 0.75f;
        private static final int INITIAL_CAPACITY = 32;
        private static final boolean ACCESS_ORDER = true;

        public CacheMap() {
            super(INITIAL_CAPACITY, LOAD_FACTOR, ACCESS_ORDER);
        }

        protected boolean removeEldestEntry(Map.Entry<IArtifactFacade, InstallableUnitCacheItem> eldest) {
            return size() > ARTIFACT_CACHE_SIZE;
        }
    }

    /**
     * Value object for the published artifact cache
     * 
     * @author liptak
     */
    private static class InstallableUnitCacheItem {
        private IInstallableUnit installableUnit;
        private IArtifactDescriptor artifactDescriptor;

        public InstallableUnitCacheItem(IInstallableUnit installableUnit, IArtifactDescriptor artifactDescriptor) {
            super();
            this.installableUnit = installableUnit;
            this.artifactDescriptor = artifactDescriptor;
        }

        public IInstallableUnit getInstallableUnit() {
            return installableUnit;
        }

        public IArtifactDescriptor getArtifactDescriptor() {
            return artifactDescriptor;
        }
    }
}
