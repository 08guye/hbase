/**
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

package org.apache.hadoop.hbase.backup.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.backup.BackupRestoreFactory;
import org.apache.hadoop.hbase.backup.BackupType;
import org.apache.hadoop.hbase.backup.BackupUtility;
import org.apache.hadoop.hbase.backup.HBackupFileSystem;
import org.apache.hadoop.hbase.backup.impl.BackupManifest.BackupImage;
import org.apache.hadoop.hbase.backup.master.LogRollMasterProcedureManager;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.classification.InterfaceStability;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.protobuf.generated.HBaseProtos;
import org.apache.hadoop.hbase.protobuf.generated.HBaseProtos.SnapshotDescription;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.zookeeper.KeeperException.NoNodeException;

/**
 * A Handler to carry the operations of backup progress
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public class BackupHandler implements Callable<Void> {
  private static final Log LOG = LogFactory.getLog(BackupHandler.class);

  // backup phase
  // for overall backup (for table list, some table may go online, while some may go offline)
  protected static enum BackupPhase {
    REQUEST, SNAPSHOT, PREPARE_INCREMENTAL, SNAPSHOTCOPY, INCREMENTAL_COPY, STORE_MANIFEST;
  }

  // backup status flag
  public static enum BackupState {
    WAITING, RUNNING, COMPLETE, FAILED, CANCELLED;
  }

  protected final BackupContext backupContext;
  private final BackupManager backupManager;
  private final Configuration conf;
  private final Connection conn;

  public BackupHandler(BackupContext backupContext,
      BackupManager backupManager, Configuration conf, Connection connection) {
    this.backupContext = backupContext;
    this.backupManager = backupManager;
    this.conf = conf;
    this.conn = connection;
  }

  public BackupContext getBackupContext() {
    return backupContext;
  }

  @Override
  public Void call() throws Exception {
    try(Admin admin = conn.getAdmin()) {
      // overall backup begin
      this.beginBackup(backupContext);
      HashMap<String, Long> newTimestamps = null;
      // handle full or incremental backup for table or table list
      if (backupContext.getType() == BackupType.FULL) {
        String savedStartCode = null;
        boolean firstBackup = false;
        // do snapshot for full table backup

        try {
          savedStartCode = backupManager.readBackupStartCode();
          firstBackup = savedStartCode == null;
          if (firstBackup) {
            // This is our first backup. Let's put some marker on ZK so that we can hold the logs
            // while we do the backup.
            backupManager.writeBackupStartCode(0L);
          }
          // We roll log here before we do the snapshot. It is possible there is duplicate data
          // in the log that is already in the snapshot. But if we do it after the snapshot, we
          // could have data loss.
          // A better approach is to do the roll log on each RS in the same global procedure as
          // the snapshot.
          LOG.info("Execute roll log procedure for full backup ...");
          admin.execProcedure(LogRollMasterProcedureManager.ROLLLOG_PROCEDURE_SIGNATURE,
            LogRollMasterProcedureManager.ROLLLOG_PROCEDURE_NAME, new HashMap<String, String>());
          newTimestamps = backupManager.readRegionServerLastLogRollResult();
          if (firstBackup) {
            // Updates registered log files
            // We record ALL old WAL files as registered, because
            // this is a first full backup in the system and these
            // files are not needed for next incremental backup
            List<String> logFiles = BackupUtil.getWALFilesOlderThan(conf, newTimestamps);
            backupManager.recordWALFiles(logFiles);
          }
          this.snapshotForFullBackup(backupContext);
        } catch (BackupException e) {
          // fail the overall backup and return
          this.failBackup(backupContext, e, "Unexpected BackupException : ");
          return null;
        }

        // update the faked progress currently for snapshot done
        updateProgress(backupContext, backupManager, 10, 0);
        // do snapshot copy
        try {
          this.snapshotCopy(backupContext);
        } catch (Exception e) {
          // fail the overall backup and return
          this.failBackup(backupContext, e, "Unexpected BackupException : ");
          return null;
        }
        // Updates incremental backup table set
        backupManager.addIncrementalBackupTableSet(backupContext.getTables());

      } else if (backupContext.getType() == BackupType.INCREMENTAL) {
        LOG.debug("For incremental backup, current table set is "
            + backupManager.getIncrementalBackupTableSet());
        // do incremental table backup preparation
        backupContext.setPhase(BackupPhase.PREPARE_INCREMENTAL);
        // avoid action if has been cancelled
        if (backupContext.isCancelled()) {
          return null;
        }
        try {
          IncrementalBackupManager incrBackupManager = new IncrementalBackupManager(backupManager);

          newTimestamps = incrBackupManager.getIncrBackupLogFileList(backupContext);
        } catch (Exception e) {
          // fail the overall backup and return
          this.failBackup(backupContext, e, "Unexpected Exception : ");
          return null;
        }
        // update the faked progress currently for incremental preparation done
        updateProgress(backupContext, backupManager, 10, 0);

        // do incremental copy
        try {
          // copy out the table and region info files for each table
          BackupUtil.copyTableRegionInfo(backupContext, conf);
          this.incrementalCopy(backupContext);
          // Save list of WAL files copied
          backupManager.recordWALFiles(backupContext.getIncrBackupFileList());
        } catch (Exception e) {
          // fail the overall backup and return
          this.failBackup(backupContext, e, "Unexpected exception doing incremental copy : ");
          return null;
        }
      }

      // set overall backup status: complete. Here we make sure to complete the backup. After this
      // checkpoint, even if entering cancel process, will let the backup finished
      backupContext.setState(BackupState.COMPLETE);

      if (backupContext.getType() == BackupType.INCREMENTAL) {
        // Set the previousTimestampMap which is before this current log roll to the manifest.
        HashMap<TableName, HashMap<String, Long>> previousTimestampMap =
            backupManager.readLogTimestampMap();
        backupContext.setIncrTimestampMap(previousTimestampMap);
      }

      // The table list in backupContext is good for both full backup and incremental backup.
      // For incremental backup, it contains the incremental backup table set.
      backupManager.writeRegionServerLogTimestamp(backupContext.getTables(), newTimestamps);

      HashMap<TableName, HashMap<String, Long>> newTableSetTimestampMap =
          backupManager.readLogTimestampMap();

      Long newStartCode =
          BackupUtility.getMinValue(BackupUtil.getRSLogTimestampMins(newTableSetTimestampMap));
      backupManager.writeBackupStartCode(newStartCode);

      // backup complete
      this.completeBackup(backupContext);
    } catch (Exception e) {
      // even during completing backup (#completeBackup(backupContext)), exception may occur, or
      // exception occur during other process, fail the backup finally
      this.failBackup(backupContext, e, "Error caught during backup progress: ");
    }
    return null;
  }

  /**
   * Begin the overall backup.
   * @param backupContext backup context
   * @throws IOException exception
   */
  private void beginBackup(BackupContext backupContext) throws IOException {
    // set the start timestamp of the overall backup
    long startTs = EnvironmentEdgeManager.currentTime();
    backupContext.setStartTs(startTs);
    // set overall backup status: ongoing
    backupContext.setState(BackupState.RUNNING);
    LOG.info("Backup " + backupContext.getBackupId() + " started at " + startTs + ".");

    backupManager.updateBackupStatus(backupContext);
    if (LOG.isDebugEnabled()) {
      LOG.debug("Backup session " + backupContext.getBackupId() + " has been started.");
    }
  }

  /**
   * Snapshot for full table backup.
   * @param backupContext backup context
   * @throws IOException exception
   */
  private void snapshotForFullBackup(BackupContext backupContext) throws IOException {
    LOG.info("HBase snapshot full backup for " + backupContext.getBackupId());

    // avoid action if has been cancelled
    if (backupContext.isCancelled()) {
      return;
    }

    try (Admin admin = conn.getAdmin()) {
      // we do HBase snapshot for tables in the table list one by one currently
      for (TableName table : backupContext.getTables()) {
        // avoid action if it has been cancelled
        if (backupContext.isCancelled()) {
          return;
        }

        HBaseProtos.SnapshotDescription backupSnapshot;

        // wrap a SnapshotDescription for offline/online snapshot
        backupSnapshot = this.wrapSnapshotDescription(table);

        try {
          // Kick off snapshot for backup
          admin.snapshot(backupSnapshot);
        } catch (Exception e) {
          LOG.error("Snapshot failed to create " + getMessage(e));

          // currently, we fail the overall backup if any table in the list failed, so throw the
          // exception out for overall backup failing
          throw new BackupException("Backup snapshot failed on table " + table, e);
        }

        // set the snapshot name in BackupStatus of this table, only after snapshot success.
        backupContext.setSnapshotName(table, backupSnapshot.getName());
      }
    }
  }

  /**
   * Fail the overall backup.
   * @param backupContext backup context
   * @param e exception
   * @throws Exception exception
   */
  private void failBackup(BackupContext backupContext, Exception e, String msg) throws Exception {
    LOG.error(msg + getMessage(e));
    // If this is a cancel exception, then we've already cleaned.

    if (this.backupContext.getState().equals(BackupState.CANCELLED)) {
      return;
    }

    // set the failure timestamp of the overall backup
    backupContext.setEndTs(EnvironmentEdgeManager.currentTime());

    // set failure message
    backupContext.setFailedMsg(e.getMessage());

    // set overall backup status: failed
    backupContext.setState(BackupState.FAILED);

    // compose the backup failed data
    String backupFailedData =
        "BackupId=" + backupContext.getBackupId() + ",startts=" + backupContext.getStartTs()
        + ",failedts=" + backupContext.getEndTs() + ",failedphase=" + backupContext.getPhase()
        + ",failedmessage=" + backupContext.getFailedMsg();
    LOG.error(backupFailedData);

    backupManager.updateBackupStatus(backupContext);

    // if full backup, then delete HBase snapshots if there already have snapshots taken
    // and also clean up export snapshot log files if exist
    if (backupContext.getType() == BackupType.FULL) {
      this.deleteSnapshot(backupContext);
      this.cleanupExportSnapshotLog();
    } /*
     * else { // support incremental backup code in future jira // TODO. See HBASE-14124 }
     */

    // clean up the uncompleted data at target directory if the ongoing backup has already entered
    // the copy phase
    // For incremental backup, DistCp logs will be cleaned with the targetDir.
    this.cleanupTargetDir();

    LOG.info("Backup " + backupContext.getBackupId() + " failed.");
  }

  /**
   * Update the ongoing back token znode with new progress.
   * @param backupContext backup context
   * 
   * @param newProgress progress
   * @param bytesCopied bytes copied
   * @throws NoNodeException exception
   */
  public static void updateProgress(BackupContext backupContext, BackupManager backupManager,
      int newProgress, long bytesCopied) throws IOException {
    // compose the new backup progress data, using fake number for now
    String backupProgressData = newProgress + "%";

    backupContext.setProgress(newProgress);
    backupManager.updateBackupStatus(backupContext);
    LOG.debug("Backup progress data \"" + backupProgressData
      + "\" has been updated to hbase:backup for " + backupContext.getBackupId());
  }

  /**
   * Complete the overall backup.
   * @param backupContext backup context
   * @throws Exception exception
   */
  private void completeBackup(BackupContext backupContext) throws Exception {

    // set the complete timestamp of the overall backup
    backupContext.setEndTs(EnvironmentEdgeManager.currentTime());
    // set overall backup status: complete
    backupContext.setState(BackupState.COMPLETE);
    // add and store the manifest for the backup
    this.addManifest(backupContext);

    // after major steps done and manifest persisted, do convert if needed for incremental backup
    /* in-fly convert code here, provided by future jira */
    LOG.debug("in-fly convert code here, provided by future jira");

    // compose the backup complete data
    String backupCompleteData =
        this.obtainBackupMetaDataStr(backupContext) + ",startts=" + backupContext.getStartTs()
        + ",completets=" + backupContext.getEndTs() + ",bytescopied="
        + backupContext.getTotalBytesCopied();
    if (LOG.isDebugEnabled()) {
      LOG.debug("Backup " + backupContext.getBackupId() + " finished: " + backupCompleteData);
    }
    backupManager.updateBackupStatus(backupContext);

    // when full backup is done:
    // - delete HBase snapshot
    // - clean up directories with prefix "exportSnapshot-", which are generated when exporting
    // snapshots
    if (backupContext.getType() == BackupType.FULL) {
      this.deleteSnapshot(backupContext);
      this.cleanupExportSnapshotLog();
    } else if (backupContext.getType() == BackupType.INCREMENTAL) {
      this.cleanupDistCpLog();
    }

    LOG.info("Backup " + backupContext.getBackupId() + " completed.");
  }

  /**
   * Get backup request meta data dir as string.
   * @param backupContext backup context
   * @return meta data dir
   */
  private String obtainBackupMetaDataStr(BackupContext backupContext) {
    StringBuffer sb = new StringBuffer();
    sb.append("type=" + backupContext.getType() + ",tablelist=");
    for (TableName table : backupContext.getTables()) {
      sb.append(table + ";");
    }
    if (sb.lastIndexOf(";") > 0) {
      sb.delete(sb.lastIndexOf(";"), sb.lastIndexOf(";") + 1);
    }
    sb.append(",targetRootDir=" + backupContext.getTargetRootDir());

    return sb.toString();
  }

  /**
   * Do snapshot copy.
   * @param backupContext backup context
   * @throws Exception exception
   */
  private void snapshotCopy(BackupContext backupContext) throws Exception {
    LOG.info("Snapshot copy is starting.");

    // set overall backup phase: snapshot_copy
    backupContext.setPhase(BackupPhase.SNAPSHOTCOPY);

    // avoid action if has been cancelled
    if (backupContext.isCancelled()) {
      return;
    }

    // call ExportSnapshot to copy files based on hbase snapshot for backup
    // ExportSnapshot only support single snapshot export, need loop for multiple tables case
    BackupCopyService copyService = BackupRestoreFactory.getBackupCopyService(conf);

    // number of snapshots matches number of tables
    float numOfSnapshots = backupContext.getSnapshotNames().size();

    LOG.debug("There are " + (int) numOfSnapshots + " snapshots to be copied.");

    for (TableName table : backupContext.getTables()) {
      // Currently we simply set the sub copy tasks by counting the table snapshot number, we can
      // calculate the real files' size for the percentage in the future.
      // TODO this below
      // backupCopier.setSubTaskPercntgInWholeTask(1f / numOfSnapshots);
      int res = 0;
      String[] args = new String[4];
      args[0] = "-snapshot";
      args[1] = backupContext.getSnapshotName(table);
      args[2] = "-copy-to";
      args[3] = backupContext.getBackupStatus(table).getTargetDir();

      LOG.debug("Copy snapshot " + args[1] + " to " + args[3]);
      res = copyService.copy(backupContext, backupManager, conf, BackupCopyService.Type.FULL, args);
      // if one snapshot export failed, do not continue for remained snapshots
      if (res != 0) {
        LOG.error("Exporting Snapshot " + args[1] + " failed with return code: " + res + ".");

        throw new IOException("Failed of exporting snapshot " + args[1] + " to " + args[3]
            + " with reason code " + res);
      }

      LOG.info("Snapshot copy " + args[1] + " finished.");
    }
  }

  /**
   * Wrap a SnapshotDescription for a target table.
   * @param table table
   * @return a SnapshotDescription especially for backup.
   */
  private SnapshotDescription wrapSnapshotDescription(TableName tableName) {
    // Mock a SnapshotDescription from backupContext to call SnapshotManager function,
    // Name it in the format "snapshot_<timestamp>_<table>"
    HBaseProtos.SnapshotDescription.Builder builder = HBaseProtos.SnapshotDescription.newBuilder();
    builder.setTable(tableName.getNameAsString());
    builder.setName("snapshot_" + Long.toString(EnvironmentEdgeManager.currentTime()) + "_"
        + tableName.getNamespaceAsString() + "_" + tableName.getQualifierAsString());
    HBaseProtos.SnapshotDescription backupSnapshot = builder.build();

    LOG.debug("Wrapped a SnapshotDescription " + backupSnapshot.getName()
      + " from backupContext to request snapshot for backup.");

    return backupSnapshot;
  }

  /**
   * Delete HBase snapshot for backup.
   * @param backupCtx backup context
   * @throws Exception exception
   */
  private void deleteSnapshot(BackupContext backupCtx) throws IOException {

    LOG.debug("Trying to delete snapshot for full backup.");
    Connection conn = null;
    Admin admin = null;
    try {
      conn = ConnectionFactory.createConnection(conf);
      admin = conn.getAdmin();
      for (String snapshotName : backupCtx.getSnapshotNames()) {
        if (snapshotName == null) {
          continue;
        }
        LOG.debug("Trying to delete snapshot: " + snapshotName);
        admin.deleteSnapshot(snapshotName);
        LOG.debug("Deleting the snapshot " + snapshotName + " for backup "
            + backupCtx.getBackupId() + " succeeded.");
      }
    } finally {
      if (admin != null) {
        admin.close();
      }
      if (conn != null) {
        conn.close();
      }
    }
  }

  /**
   * Clean up directories with prefix "exportSnapshot-", which are generated when exporting
   * snapshots.
   * @throws IOException exception
   */
  private void cleanupExportSnapshotLog() throws IOException {
    FileSystem fs = FSUtils.getCurrentFileSystem(conf);
    Path stagingDir =
        new Path(conf.get(BackupRestoreConstants.CONF_STAGING_ROOT, fs.getWorkingDirectory()
          .toString()));
    FileStatus[] files = FSUtils.listStatus(fs, stagingDir);
    if (files == null) {
      return;
    }
    for (FileStatus file : files) {
      if (file.getPath().getName().startsWith("exportSnapshot-")) {
        LOG.debug("Delete log files of exporting snapshot: " + file.getPath().getName());
        if (FSUtils.delete(fs, file.getPath(), true) == false) {
          LOG.warn("Can not delete " + file.getPath());
        }
      }
    }
  }

  /**
   * Clean up directories with prefix "_distcp_logs-", which are generated when DistCp copying
   * hlogs.
   * @throws IOException exception
   */
  private void cleanupDistCpLog() throws IOException {
    Path rootPath = new Path(backupContext.getHLogTargetDir()).getParent();
    FileSystem fs = FileSystem.get(rootPath.toUri(), conf);
    FileStatus[] files = FSUtils.listStatus(fs, rootPath);
    if (files == null) {
      return;
    }
    for (FileStatus file : files) {
      if (file.getPath().getName().startsWith("_distcp_logs")) {
        LOG.debug("Delete log files of DistCp: " + file.getPath().getName());
        FSUtils.delete(fs, file.getPath(), true);
      }
    }
  }

  /**
   * Clean up the uncompleted data at target directory if the ongoing backup has already entered the
   * copy phase.
   */
  private void cleanupTargetDir() {
    try {
      // clean up the uncompleted data at target directory if the ongoing backup has already entered
      // the copy phase
      LOG.debug("Trying to cleanup up target dir. Current backup phase: "
          + backupContext.getPhase());
      if (backupContext.getPhase().equals(BackupPhase.SNAPSHOTCOPY)
          || backupContext.getPhase().equals(BackupPhase.INCREMENTAL_COPY)
          || backupContext.getPhase().equals(BackupPhase.STORE_MANIFEST)) {
        FileSystem outputFs =
            FileSystem.get(new Path(backupContext.getTargetRootDir()).toUri(), conf);

        // now treat one backup as a transaction, clean up data that has been partially copied at
        // table level
        for (TableName table : backupContext.getTables()) {
          Path targetDirPath =
              new Path(HBackupFileSystem.getTableBackupDir(backupContext.getTargetRootDir(),
                backupContext.getBackupId(), table));
          if (outputFs.delete(targetDirPath, true)) {
            LOG.info("Cleaning up uncompleted backup data at " + targetDirPath.toString()
              + " done.");
          } else {
            LOG.info("No data has been copied to " + targetDirPath.toString() + ".");
          }

          Path tableDir = targetDirPath.getParent();
          FileStatus[] backups = FSUtils.listStatus(outputFs, tableDir);
          if (backups == null || backups.length == 0) {
            outputFs.delete(tableDir, true);
            LOG.debug(tableDir.toString() + " is empty, remove it.");
          }
        }
      }

    } catch (IOException e1) {
      LOG.error("Cleaning up uncompleted backup data of " + backupContext.getBackupId() + " at "
          + backupContext.getTargetRootDir() + " failed due to " + e1.getMessage() + ".");
    }
  }

  /**
   * Add manifest for the current backup. The manifest is stored
   * within the table backup directory.
   * @param backupContext The current backup context
   * @throws IOException exception
   * @throws BackupException exception
   */
  private void addManifest(BackupContext backupContext) throws IOException, BackupException {
    // set the overall backup phase : store manifest
    backupContext.setPhase(BackupPhase.STORE_MANIFEST);

    // avoid action if has been cancelled
    if (backupContext.isCancelled()) {
      return;
    }

    BackupManifest manifest;

    // Since we have each table's backup in its own directory structure,
    // we'll store its manifest with the table directory.
    for (TableName table : backupContext.getTables()) {
      manifest = new BackupManifest(backupContext, table);
      ArrayList<BackupImage> ancestors = this.backupManager.getAncestors(backupContext, table);
      for (BackupImage image : ancestors) {
        manifest.addDependentImage(image);
      }

      if (backupContext.getType() == BackupType.INCREMENTAL) {
        // We'll store the log timestamps for this table only in its manifest.
        HashMap<TableName, HashMap<String, Long>> tableTimestampMap =
            new HashMap<TableName, HashMap<String, Long>>();
        tableTimestampMap.put(table, backupContext.getIncrTimestampMap().get(table));
        manifest.setIncrTimestampMap(tableTimestampMap);
      }
      manifest.store(conf);
    }

    // For incremental backup, we store a overall manifest in
    // <backup-root-dir>/WALs/<backup-id>
    // This is used when created the next incremental backup
    if (backupContext.getType() == BackupType.INCREMENTAL) {
      manifest = new BackupManifest(backupContext);
      // set the table region server start and end timestamps for incremental backup
      manifest.setIncrTimestampMap(backupContext.getIncrTimestampMap());
      ArrayList<BackupImage> ancestors = this.backupManager.getAncestors(backupContext);
      for (BackupImage image : ancestors) {
        manifest.addDependentImage(image);
      }
      manifest.store(conf);
    }
  }

  /**
   * Do incremental copy.
   * @param backupContext backup context
   */
  private void incrementalCopy(BackupContext backupContext) throws Exception {

    LOG.info("Incremental copy is starting.");

    // set overall backup phase: incremental_copy
    backupContext.setPhase(BackupPhase.INCREMENTAL_COPY);

    // avoid action if has been cancelled
    if (backupContext.isCancelled()) {
      return;
    }

    // get incremental backup file list and prepare parms for DistCp
    List<String> incrBackupFileList = backupContext.getIncrBackupFileList();
    // filter missing files out (they have been copied by previous backups)
    incrBackupFileList = filterMissingFiles(incrBackupFileList);
    String[] strArr = incrBackupFileList.toArray(new String[incrBackupFileList.size() + 1]);
    strArr[strArr.length - 1] = backupContext.getHLogTargetDir();

    BackupCopyService copyService = BackupRestoreFactory.getBackupCopyService(conf);
    int res = copyService.copy(backupContext, backupManager, conf,
      BackupCopyService.Type.INCREMENTAL, strArr);

    if (res != 0) {
      LOG.error("Copy incremental log files failed with return code: " + res + ".");
      throw new IOException("Failed of Hadoop Distributed Copy from " + incrBackupFileList + " to "
          + backupContext.getHLogTargetDir());
    }
    LOG.info("Incremental copy from " + incrBackupFileList + " to "
        + backupContext.getHLogTargetDir() + " finished.");

  }

  private List<String> filterMissingFiles(List<String> incrBackupFileList) throws IOException {
    FileSystem fs = FileSystem.get(conf);
    List<String> list = new ArrayList<String>();
    for(String file : incrBackupFileList){
      if(fs.exists(new Path(file))){
        list.add(file);
      } else{
        LOG.warn("Can't find file: "+file);
      }
    }
    return list;
  }

  private String getMessage(Exception e) {
    String msg = e.getMessage();
    if (msg == null || msg.equals("")) {
      msg = e.getClass().getName();
    }
    return msg;
  }
}
