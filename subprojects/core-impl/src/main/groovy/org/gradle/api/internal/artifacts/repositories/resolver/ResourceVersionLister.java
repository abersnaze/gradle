/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.artifacts.repositories.resolver;

import org.apache.ivy.core.IvyPatternHelper;
import org.gradle.api.artifacts.ModuleIdentifier;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ResourceAwareResolveResult;
import org.gradle.api.internal.artifacts.metadata.IvyArtifactName;
import org.gradle.internal.resource.ExternalResourceName;
import org.gradle.internal.resource.ResourceException;
import org.gradle.internal.resource.transport.ExternalResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ResourceVersionLister implements VersionLister {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResourceVersionLister.class);
    private static final String REVISION_TOKEN = IvyPatternHelper.getTokenString(IvyPatternHelper.REVISION_KEY);
    public static final int REV_TOKEN_LENGTH = REVISION_TOKEN.length();

    private final ExternalResourceRepository repository;
    private final String fileSeparator = "/";

    public ResourceVersionLister(ExternalResourceRepository repository) {
        this.repository = repository;
    }

    public VersionList getVersionList(final ModuleIdentifier module, final ResourceAwareResolveResult result) {
        return new DefaultVersionList() {
            final Set<URI> directories = new HashSet<URI>();

            public void visit(ResourcePattern pattern, IvyArtifactName artifact) throws ResourceException {
                ExternalResourceName versionListPattern = pattern.toVersionListPattern(module, artifact);
                LOGGER.debug("Listing all in {}", versionListPattern);
                try {
                    List<String> versionStrings = listRevisionToken(versionListPattern);
                    for (String versionString : versionStrings) {
                        add(versionString);
                    }
                } catch (ResourceException e) {
                    throw e;
                } catch (Exception e) {
                    throw new ResourceException(String.format("Could not list versions using %s.", pattern), e);
                }
            }

            // lists all the values a revision token listed by a given url lister
            private List<String> listRevisionToken(ExternalResourceName versionListPattern) throws IOException {
                String pattern = versionListPattern.getPath();
                if (!pattern.contains(REVISION_TOKEN)) {
                    LOGGER.debug("revision token not defined in pattern {}.", pattern);
                    return Collections.emptyList();
                }
                String prefix = pattern.substring(0, pattern.indexOf(REVISION_TOKEN));
                if (revisionMatchesDirectoryName(pattern)) {
                    ExternalResourceName parent = versionListPattern.getRoot().resolve(prefix);
                    return listAll(parent.getUri());
                } else {
                    int parentFolderSlashIndex = prefix.lastIndexOf(fileSeparator);
                    String revisionParentFolder = parentFolderSlashIndex == -1 ? "" : prefix.substring(0, parentFolderSlashIndex + 1);
                    ExternalResourceName parent = versionListPattern.getRoot().resolve(revisionParentFolder);
                    LOGGER.debug("using {} to list all in {} ", repository, revisionParentFolder);
                    if (!directories.add(parent.getUri())) {
                        return Collections.emptyList();
                    }
                    result.attempted(parent.getUri().toString());
                    List<String> all = repository.list(parent.getUri());
                    if (all == null) {
                        return Collections.emptyList();
                    }
                    LOGGER.debug("found {} urls", all.size());
                    Pattern regexPattern = createRegexPattern(pattern, parentFolderSlashIndex);
                    List<String> ret = filterMatchedValues(all, regexPattern);
                    LOGGER.debug("{} matched {}" + pattern, ret.size(), pattern);
                    return ret;
                }
            }

            private List<String> filterMatchedValues(List<String> all, final Pattern p) {
                List<String> ret = new ArrayList<String>(all.size());
                for (String path : all) {
                    Matcher m = p.matcher(path);
                    if (m.matches()) {
                        String value = m.group(1);
                        ret.add(value);
                    }
                }
                return ret;
            }

            private Pattern createRegexPattern(String pattern, int prefixLastSlashIndex) {
                int endNameIndex = pattern.indexOf(fileSeparator, prefixLastSlashIndex + 1);
                String namePattern;
                if (endNameIndex != -1) {
                    namePattern = pattern.substring(prefixLastSlashIndex + 1, endNameIndex);
                } else {
                    namePattern = pattern.substring(prefixLastSlashIndex + 1);
                }
                namePattern = namePattern.replaceAll("\\.", "\\\\.");

                String acceptNamePattern = namePattern.replaceAll("\\[revision\\]", "(.+)");
                return Pattern.compile(acceptNamePattern);
            }

            private boolean revisionMatchesDirectoryName(String pattern) {
                int startToken = pattern.indexOf(REVISION_TOKEN);
                if (startToken > 0 && !pattern.substring(startToken - 1, startToken).equals(fileSeparator)) {
                    // previous character is not a separator
                    return false;
                }
                int endToken = startToken + REV_TOKEN_LENGTH;
                if (endToken < pattern.length() && !pattern.substring(endToken, endToken + 1).equals(fileSeparator)) {
                    // next character is not a separator
                    return false;
                }
                return true;
            }

            private List<String> listAll(URI parent) throws IOException {
                if (!directories.add(parent)) {
                    return Collections.emptyList();
                }
                LOGGER.debug("using {} to list all in {}", repository, parent);
                result.attempted(parent.toString());
                List<String> paths = repository.list(parent);
                if (paths == null) {
                    return Collections.emptyList();
                }
                LOGGER.debug("found {} resources", paths.size());
                return paths;
            }
        };
    }
}
