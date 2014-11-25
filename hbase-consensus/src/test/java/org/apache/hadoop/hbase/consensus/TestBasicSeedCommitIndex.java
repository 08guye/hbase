package org.apache.hadoop.hbase.consensus;

import static junit.framework.Assert.fail;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.consensus.client.QuorumClient;
import org.apache.hadoop.hbase.consensus.quorum.RaftQuorumContext;
import org.apache.hadoop.hbase.consensus.server.LocalConsensusServer;
import org.apache.hadoop.hbase.regionserver.wal.WALEdit;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Threads;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestBasicSeedCommitIndex {
  private static final Logger LOG = LoggerFactory.getLogger(
    TestBasicPeerSeeding.class);

  private static final int QUORUM_SIZE = 5;
  private static final int QUORUM_MAJORITY = 3;
  private static HRegionInfo regionInfo;
  private static RaftTestUtil RAFT_TEST_UTIL = new RaftTestUtil();
  private final long seedIndex = 100;

  @Before
  public void setUp() throws Exception {
    RAFT_TEST_UTIL.getConf().setLong(
      HConstants.CONSENSUS_TRANCTION_LOG_RETENTION_TIME_KEY, 1000);
    RAFT_TEST_UTIL.createRaftCluster(QUORUM_SIZE);
    RAFT_TEST_UTIL.assertAllServersRunning();
    RAFT_TEST_UTIL.setUsePeristentLog(true);
    regionInfo = RAFT_TEST_UTIL.initializePeers();
    RAFT_TEST_UTIL.addQuorum(regionInfo, null);
    RAFT_TEST_UTIL.setSeedIndex(seedIndex);
    RAFT_TEST_UTIL.startQuorum(regionInfo);

  }

  @After
  public void tearDown() throws Exception {
    RAFT_TEST_UTIL.shutdown();
  }

  @Test(timeout=50000)
  public void testSingleCommit() throws IOException {

    // Wait for leader

    RaftQuorumContext c5 = RAFT_TEST_UTIL.getRaftQuorumContextByRank(regionInfo, 5);
    while (!c5.isLeader()) {
      Threads.sleep(1000);
    }

    // Wait till we purge the seed log file
    int count = 0;
    List<RaftQuorumContext> peers = RAFT_TEST_UTIL.getQuorumContexts(regionInfo);

    while (count != QUORUM_SIZE) {
      Threads.sleep(1000);
      count = 0;
      for (RaftQuorumContext p : peers) {
        if (p.getLogManager().getFirstIndex() >= 101) {
          ++count;
        }
      }
      RAFT_TEST_UTIL.dumpStates(regionInfo);
    }

    // At this point the state should
    // [rank: 5] ; LEADER ; { Uncommitted [101, 101] }
    // [rank: 4] ; FOLLOWER ; { Uncommitted [101, 101] }
    // [rank: 3] ; FOLLOWER ; { Uncommitted [101, 101] }
    // [rank: 2] ; FOLLOWER ; { Uncommitted [101, 101] }
    // [rank: 1] ; FOLLOWER ; { Uncommitted [101, 101] }

    // Let's stop the leader
    LocalConsensusServer s5 = RAFT_TEST_UTIL.stopLocalConsensusServer(regionInfo,
      5);

    RaftQuorumContext c4 = RAFT_TEST_UTIL.getRaftQuorumContextByRank(regionInfo, 4);

    while (!c4.isLeader()) {
      Threads.sleep(1000);
    }

    c5 = RAFT_TEST_UTIL.restartLocalConsensusServer(s5, regionInfo,
      c5.getMyAddress());

    while (!c5.isLeader()) {
      Threads.sleep(1000);
    }

    // Wait for logs to be verified
    // Verify logs are identical across all the quorum members
    while (!RAFT_TEST_UTIL.verifyLogs(regionInfo.getQuorumInfo(), QUORUM_SIZE)) {
      Threads.sleep(1000);
      System.out.println("Verifying logs ....");
      Assert
        .assertTrue("Rank 5 shall be the leader of the quorum", c5.isLeader());
    }
  }
}
