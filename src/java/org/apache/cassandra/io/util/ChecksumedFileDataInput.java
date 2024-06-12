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

package org.apache.cassandra.io.util;

import java.io.IOException;
import java.util.function.Supplier;
import java.util.zip.Checksum;

public class ChecksumedFileDataInput extends ChecksumedDataInputPlus implements FileDataInput
{
    public ChecksumedFileDataInput(FileDataInput delegate, Checksum checksum)
    {
        super(delegate, checksum);
    }

    public ChecksumedFileDataInput(FileDataInput delegate, Supplier<Checksum> fn)
    {
        super(delegate, fn);
    }

    @Override
    public FileDataInput delegate()
    {
        return (FileDataInput) super.delegate();
    }

    @Override
    public String getPath()
    {
        return delegate().getPath();
    }

    @Override
    public boolean isEOF() throws IOException
    {
        return delegate().isEOF();
    }

    @Override
    public long bytesRemaining() throws IOException
    {
        return delegate().bytesRemaining();
    }

    @Override
    public void seek(long pos) throws IOException
    {
        resetChecksum();
        delegate().seek(pos);
    }

    @Override
    public long getFilePointer()
    {
        return delegate().getFilePointer();
    }

    @Override
    public void close() throws IOException
    {
        resetChecksum();
        delegate().close();
    }

    @Override
    public DataPosition mark()
    {
        resetChecksum();
        return delegate().mark();
    }

    @Override
    public void reset(DataPosition mark) throws IOException
    {
        resetChecksum();
        delegate().reset(mark);
    }

    @Override
    public long bytesPastMark(DataPosition mark)
    {
        return delegate().bytesPastMark(mark);
    }
}