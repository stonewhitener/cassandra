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

package org.apache.cassandra.net;

import java.util.Iterator;

import org.apache.cassandra.locator.InetAddressAndPort;
import org.apache.cassandra.repair.SharedContext;

public class MessagingUtils
{
    /**
     * Candidate iterator that would try all endpoints known to be alive first, and then try all endpoints
     * in a round-robin manner.
     */
    public static Iterator<InetAddressAndPort> tryAliveFirst(SharedContext context, Iterable<InetAddressAndPort> peers)
    {
        return new Iterator<>()
        {
            boolean firstRun = true;
            Iterator<InetAddressAndPort> iter = peers.iterator();
            boolean isEmpty = !iter.hasNext();

            public boolean hasNext()
            {
                return !isEmpty;
            }

            public InetAddressAndPort next()
            {
                // At first, try all alive nodes
                if (firstRun)
                {
                    while (iter.hasNext())
                    {
                        InetAddressAndPort candidate = iter.next();
                        if (context.failureDetector().isAlive(candidate))
                            return candidate;
                    }
                    firstRun = false;
                }

                // After that, cycle through all nodes
                if (!iter.hasNext())
                    iter = peers.iterator();

                return iter.next();
            }
        };
    }
}