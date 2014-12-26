package org.apache.hadoop.hbase.consensus;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */


import junit.framework.Assert;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.consensus.log.LogFileViewer;
import org.apache.hadoop.hbase.consensus.log.LogReader;
import org.apache.hadoop.hbase.consensus.log.LogWriter;
import org.apache.hadoop.hbase.io.hfile.Compression;
import org.apache.hadoop.hbase.regionserver.wal.WALEdit;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.List;

public class TestLogFileViewer {
  private static final Logger
    LOG = LoggerFactory.getLogger(TestLogFileViewer.class);

  @Test
  public void testViewer() throws IOException {
    final int numTXNs = 100;
    final KeyValue.KVComparator comparator = new KeyValue.KVComparator();

    // Initialize the writer
    File file = new File("TestLogFileViewer");
    RandomAccessFile raf = new RandomAccessFile(file, "rw");
    LogWriter writer = new LogWriter(raf, false);

    // Generate the header
    final long initialIndex = 0;
    final long term = 1;
    writer.writeFileHeader(term, initialIndex);

    // Write the numTXNs to the log file
    long curIndex, filePosition;
    List<WALEdit> txns;
    WALEdit edit;
    for (curIndex = initialIndex; curIndex < numTXNs; curIndex++) {
      edit = new WALEdit();
      edit.add(new KeyValue(Bytes.toBytes(curIndex), curIndex));
      txns = Arrays.asList(edit);
      writer.append(curIndex, WALEdit.serializeToByteBuffer(txns, 1234567890L,
              Compression.Algorithm.NONE));

      // Test the truncate for every 10 entries;
      if (curIndex % 10 == 0) {
        // Write some dummy data to be truncated
        filePosition = writer.getCurrentPosition();

        edit = new WALEdit();
        edit.add(new KeyValue(Bytes.toBytes("ToBeTruncated"), System.currentTimeMillis()));
        txns = Arrays.asList(edit);

        long tmpIndex = curIndex + 1;
        long tmpOffset = writer.append(tmpIndex,
                WALEdit.serializeToByteBuffer(txns, 1234567890L,
                        Compression.Algorithm.NONE));


        Assert.assertEquals(filePosition, tmpOffset);
        writer.truncate(tmpOffset);
        Assert.assertEquals(tmpOffset, raf.getChannel().size());

        LOG.info("Truncate the log at the offset of " + tmpOffset + " for the index " + tmpIndex);
      }
    }

    // Close the writer
    writer.close();

    LogFileViewer.dumpFileInfo(file, false);
  }
}
