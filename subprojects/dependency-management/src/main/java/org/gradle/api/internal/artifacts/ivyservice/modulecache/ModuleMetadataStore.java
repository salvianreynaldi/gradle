/*
 * Copyright 2011 the original author or authors.
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
package org.gradle.api.internal.artifacts.ivyservice.modulecache;

import org.gradle.api.Action;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.internal.artifacts.ImmutableModuleIdentifierFactory;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.component.external.model.ModuleComponentResolveMetadata;
import org.gradle.internal.component.external.model.MutableModuleComponentResolveMetadata;
import org.gradle.internal.resource.local.LocallyAvailableResource;
import org.gradle.internal.resource.local.PathKeyFileStore;
import org.gradle.internal.serialize.Decoder;
import org.gradle.internal.serialize.kryo.KryoBackedDecoder;
import org.gradle.internal.serialize.kryo.KryoBackedEncoder;
import org.gradle.util.internal.SimpleMapInterner;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class ModuleMetadataStore {

    private final PathKeyFileStore metaDataStore;
    private final ModuleMetadataSerializer moduleMetadataSerializer;
    private final ImmutableModuleIdentifierFactory moduleIdentifierFactory;
    private final SimpleMapInterner stringInterner = SimpleMapInterner.threadSafe();

    public ModuleMetadataStore(PathKeyFileStore metaDataStore, ModuleMetadataSerializer moduleMetadataSerializer, ImmutableModuleIdentifierFactory moduleIdentifierFactory) {
        this.metaDataStore = metaDataStore;
        this.moduleMetadataSerializer = moduleMetadataSerializer;
        this.moduleIdentifierFactory = moduleIdentifierFactory;
    }

    public MutableModuleComponentResolveMetadata getModuleDescriptor(ModuleComponentAtRepositoryKey component) {
        String filePath = getFilePath(component);
        final LocallyAvailableResource resource = metaDataStore.get(filePath);
        if (resource != null) {
            try {
                StringDeduplicatingDecoder decoder = new StringDeduplicatingDecoder(new KryoBackedDecoder(new FileInputStream(resource.getFile())));
                try {
                    return moduleMetadataSerializer.read(decoder, moduleIdentifierFactory);
                } finally {
                    decoder.close();
                }
            } catch (Exception e) {
                throw new RuntimeException("Could not load module metadata from " + resource.getDisplayName(), e);
            }
        }
        return null;
    }

    public LocallyAvailableResource putModuleDescriptor(ModuleComponentAtRepositoryKey component, final ModuleComponentResolveMetadata metadata) {
        String filePath = getFilePath(component);
        return metaDataStore.add(filePath, new Action<File>() {
            public void execute(File moduleDescriptorFile) {
                try {
                    KryoBackedEncoder encoder = new KryoBackedEncoder(new FileOutputStream(moduleDescriptorFile));
                    try {
                        moduleMetadataSerializer.write(encoder, metadata);
                    } finally {
                        encoder.close();
                    }
                } catch (Exception e) {
                    throw UncheckedException.throwAsUncheckedException(e);
                }
            }
        });
    }

    private String getFilePath(ModuleComponentAtRepositoryKey componentId) {
        ModuleComponentIdentifier moduleComponentIdentifier = componentId.getComponentId();
        return moduleComponentIdentifier.getGroup() + "/" + moduleComponentIdentifier.getModule() + "/" + moduleComponentIdentifier.getVersion() + "/" + componentId.getRepositoryId() + "/descriptor.bin";
    }

    private class StringDeduplicatingDecoder implements Decoder, Closeable {
        private final Decoder delegate;

        private StringDeduplicatingDecoder(Decoder delegate) {
            this.delegate = delegate;
        }

        @Override
        public InputStream getInputStream() {
            return delegate.getInputStream();
        }

        @Override
        public long readLong() throws EOFException, IOException {
            return delegate.readLong();
        }

        @Override
        public long readSmallLong() throws EOFException, IOException {
            return delegate.readSmallLong();
        }

        @Override
        public int readInt() throws EOFException, IOException {
            return delegate.readInt();
        }

        @Override
        public int readSmallInt() throws EOFException, IOException {
            return delegate.readSmallInt();
        }

        @Override
        public boolean readBoolean() throws EOFException, IOException {
            return delegate.readBoolean();
        }

        @Override
        public String readString() throws EOFException, IOException {
            return stringInterner.intern(delegate.readString());
        }

        @Override
        @Nullable
        public String readNullableString() throws EOFException, IOException {
            String str = delegate.readNullableString();
            if (str != null) {
                str = stringInterner.intern(str);
            }
            return str;
        }

        @Override
        public byte readByte() throws EOFException, IOException {
            return delegate.readByte();
        }

        @Override
        public void readBytes(byte[] buffer) throws EOFException, IOException {
            delegate.readBytes(buffer);
        }

        @Override
        public void readBytes(byte[] buffer, int offset, int count) throws EOFException, IOException {
            delegate.readBytes(buffer, offset, count);
        }

        @Override
        public byte[] readBinary() throws EOFException, IOException {
            return delegate.readBinary();
        }

        @Override
        public void skipBytes(long count) throws EOFException, IOException {
            delegate.skipBytes(count);
        }

        @Override
        public void close() throws IOException {
            ((Closeable)delegate).close();
        }
    }
}
