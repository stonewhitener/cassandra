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

package org.apache.cassandra.service.accord;

import java.io.IOException;

import com.google.common.annotations.VisibleForTesting;

import accord.api.RoutingKey;
import accord.primitives.Range;
import accord.utils.Invariants;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.io.IVersionedSerializer;
import org.apache.cassandra.io.util.DataInputPlus;
import org.apache.cassandra.io.util.DataOutputPlus;
import org.apache.cassandra.schema.TableId;
import org.apache.cassandra.service.accord.api.AccordRoutingKey;
import org.apache.cassandra.service.accord.api.AccordRoutingKey.SentinelKey;

public class TokenRange extends Range.EndInclusive
{
    public TokenRange(AccordRoutingKey start, AccordRoutingKey end)
    {
        super(start, end);
    }

    public static TokenRange create(AccordRoutingKey start, AccordRoutingKey end)
    {
        Invariants.checkArgument(start.table().equals(end.table()),
                                 "Token ranges cannot cover more than one keyspace start:%s, end:%s",
                                 start, end);
        return new TokenRange(start, end);
    }

    public static TokenRange createUnsafe(AccordRoutingKey start, AccordRoutingKey end)
    {
        return new TokenRange(start, end);
    }

    public TableId table()
    {
        return start().table();
    }

    @Override
    public AccordRoutingKey start()
    {
        return (AccordRoutingKey) super.start();
    }

    @Override
    public AccordRoutingKey end()
    {
        return  (AccordRoutingKey) super.end();
    }

    public boolean isFullRange()
    {
        return start().kindOfRoutingKey() == AccordRoutingKey.RoutingKeyKind.SENTINEL && end().kindOfRoutingKey() == AccordRoutingKey.RoutingKeyKind.SENTINEL;
    }

    @VisibleForTesting
    public Range withTable(TableId table)
    {
        return new TokenRange(start().withTable(table), end().withTable(table));
    }

    public static TokenRange fullRange(TableId table)
    {
        return new TokenRange(SentinelKey.min(table), SentinelKey.max(table));
    }

    @Override
    public TokenRange newRange(RoutingKey start, RoutingKey end)
    {
        return new TokenRange((AccordRoutingKey) start, (AccordRoutingKey) end);
    }

    public org.apache.cassandra.dht.Range<Token> toKeyspaceRange ()
    {
        IPartitioner partitioner = DatabaseDescriptor.getPartitioner();
        AccordRoutingKey start = start();
        AccordRoutingKey end = end();
        Token left = start instanceof SentinelKey ? partitioner.getMinimumToken() : start.token();
        Token right = end instanceof SentinelKey ? partitioner.getMinimumToken() : end.token();
        return new org.apache.cassandra.dht.Range<>(left, right);
    }


    public static final Serializer serializer = new Serializer();

    public static final class Serializer implements IVersionedSerializer<TokenRange>
    {
        @Override
        public void serialize(TokenRange range, DataOutputPlus out, int version) throws IOException
        {
            AccordRoutingKey.serializer.serialize(range.start(), out, version);
            AccordRoutingKey.serializer.serialize(range.end(), out, version);
        }

        public void skip(DataInputPlus in, int version) throws IOException
        {
            AccordRoutingKey.serializer.skip(in, version);
            AccordRoutingKey.serializer.skip(in, version);
        }

        @Override
        public TokenRange deserialize(DataInputPlus in, int version) throws IOException
        {
            return TokenRange.create(AccordRoutingKey.serializer.deserialize(in, version),
                                     AccordRoutingKey.serializer.deserialize(in, version));
        }

        @Override
        public long serializedSize(TokenRange range, int version)
        {
            return AccordRoutingKey.serializer.serializedSize(range.start(), version)
                 + AccordRoutingKey.serializer.serializedSize(range.end(), version);
        }
    };
}