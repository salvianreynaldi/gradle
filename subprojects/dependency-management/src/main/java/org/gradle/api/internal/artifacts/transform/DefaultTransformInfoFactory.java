/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.api.internal.artifacts.transform;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.gradle.api.Action;
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.BuildableSingleResolvedArtifactSet;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.CompositeResolvedArtifactSet;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedArtifactSet;
import org.gradle.internal.operations.BuildOperationExecutor;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public class DefaultTransformInfoFactory implements TransformInfoFactory {
    private final Map<ArtifactTransformKey, TransformInfo> transformations = Maps.newConcurrentMap();
    private final BuildOperationExecutor buildOperationExecutor;

    public DefaultTransformInfoFactory(BuildOperationExecutor buildOperationExecutor) {
        this.buildOperationExecutor = buildOperationExecutor;
    }

    @Override
    public Collection<TransformInfo> getOrCreate(ResolvedArtifactSet artifactSet, ArtifactTransformer transformer) {
        final List<UserCodeBackedTransformer> transformerChain = unpackTransformerChain(transformer);
        final ImmutableList.Builder<TransformInfo> builder = ImmutableList.builder();
        CompositeResolvedArtifactSet.visitHierarchy(artifactSet, new CompositeResolvedArtifactSet.ResolvedArtifactSetVisitor() {
            @Override
            public boolean visitArtifactSet(ResolvedArtifactSet set) {
                if (set instanceof CompositeResolvedArtifactSet) {
                    return true;
                }
                if (!(set instanceof BuildableSingleResolvedArtifactSet)) {
                    throw new IllegalStateException(String.format("Expecting a %s instead of a %s",
                        BuildableSingleResolvedArtifactSet.class.getSimpleName(), set.getClass().getName()));
                }
                BuildableSingleResolvedArtifactSet singleArtifactSet = (BuildableSingleResolvedArtifactSet) set;
                ArtifactTransformKey key = new ArtifactTransformKey(singleArtifactSet.getArtifactId(), transformerChain);
                TransformInfo transformInfo = transformations.get(key);
                if (transformInfo == null) {
                    transformInfo = TransformInfo.from(transformerChain, singleArtifactSet, buildOperationExecutor);
                    transformations.put(key, transformInfo);
                }
                builder.add(transformInfo);
                return true;
            }
        });
        return builder.build();
    }

    private static List<UserCodeBackedTransformer> unpackTransformerChain(ArtifactTransformer transformer) {
        final List<UserCodeBackedTransformer> transformerChain = Lists.newArrayList();
        transformer.visitLeafTransformers(new Action<ArtifactTransformer>() {
            @Override
            public void execute(ArtifactTransformer transformer) {
                transformerChain.add((UserCodeBackedTransformer) transformer);
            }
        });
        return transformerChain;
    }

    private static class ArtifactTransformKey {
        private final ComponentArtifactIdentifier artifactIdentifier;
        private final List<UserCodeBackedTransformer> transformers;

        private ArtifactTransformKey(ComponentArtifactIdentifier artifactIdentifier, List<UserCodeBackedTransformer> transformers) {
            this.artifactIdentifier = artifactIdentifier;
            this.transformers = transformers;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            ArtifactTransformKey that = (ArtifactTransformKey) o;

            if (!artifactIdentifier.equals(that.artifactIdentifier)) {
                return false;
            }
            return transformers.equals(that.transformers);
        }

        @Override
        public int hashCode() {
            int result = artifactIdentifier.hashCode();
            result = 31 * result + transformers.hashCode();
            return result;
        }
    }
}
