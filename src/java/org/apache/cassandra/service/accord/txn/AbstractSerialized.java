/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.service.accord.txn;

import java.nio.ByteBuffer;
import java.util.Objects;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import org.apache.cassandra.io.IVersionedSerializer;
import org.apache.cassandra.service.accord.AccordSerializers;

/**
 * Item that is serialized by default
 */
@NotThreadSafe
public abstract class AbstractSerialized<T>
{
    private @Nullable final ByteBuffer bytes;
    private @Nullable T memoized = null;

    public AbstractSerialized(@Nullable ByteBuffer bytes)
    {
        this.bytes = bytes;
    }

    public AbstractSerialized(@Nullable T value)
    {
        this.bytes = value != null ? AccordSerializers.serialize(value, serializer()) : null;
        this.memoized = value;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AbstractSerialized<?> that = (AbstractSerialized<?>) o;

        return Objects.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode()
    {
        return bytes != null ? bytes.hashCode() : 0;
    }

    @Override
    public String toString()
    {
        return get().toString();
    }

    protected abstract IVersionedSerializer<T> serializer();

    protected T get()
    {
        T result = memoized;
        if (result == null && bytes != null)
            memoized = result = AccordSerializers.deserialize(bytes, serializer());
        return result;
    }

    public void unmemoize()
    {
        memoized = null;
    }

    protected ByteBuffer bytes()
    {
        return bytes;
    }
}