package com.booking.replication.pipeline;

import com.booking.replication.Configuration;
import com.booking.replication.Coordinator;
import com.booking.replication.Metrics;
import com.booking.replication.applier.Applier;
import com.booking.replication.applier.ApplierException;
import com.booking.replication.applier.HBaseApplier;
import com.booking.replication.augmenter.EventAugmenter;
//<<<<<<< HEAD
import com.booking.replication.binlog.EventPosition;
import com.booking.replication.binlog.event.QueryEventType;
import com.booking.replication.checkpoints.LastCommittedPositionCheckpoint;
import com.booking.replication.pipeline.event.handler.*;
import com.booking.replication.queues.ReplicatorQueues;
//=======
//import com.booking.replication.binlog.common.Row;
//import com.booking.replication.binlog.event.*;
//import com.booking.replication.checkpoints.LastCommittedPositionCheckpoint;
//
//>>>>>>> Migrating to binlog connector. Temporarily will support both parsers.
import com.booking.replication.replicant.ReplicantPool;
import com.booking.replication.schema.ActiveSchemaVersion;
import com.booking.replication.schema.exception.SchemaTransitionException;
import com.booking.replication.schema.exception.TableMapException;
import com.booking.replication.sql.QueryInspector;
//<<<<<<< HEAD
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.google.code.or.binlog.BinlogEventV4;
import com.google.code.or.binlog.impl.event.*;
import com.google.code.or.common.util.MySQLConstants;
import com.google.common.base.Joiner;
//=======
//import com.booking.replication.sql.exception.QueryInspectorException;
//import com.google.common.base.Joiner;

//import com.codahale.metrics.Gauge;
//import com.codahale.metrics.Meter;
//import com.codahale.metrics.MetricRegistry;
//import org.jruby.RubyProcess;
//>>>>>>> Migrating to binlog connector. Temporarily will support both parsers.
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.codahale.metrics.MetricRegistry.name;


/**
 * Pipeline Orchestrator.
 *
 * <p>Manages data flow from event producer into the applier.
 * Also manages persistence of metadata necessary for the replicator features.</p>
 *
 * <p>On each event handles:
 *      1. schema version management
 *      2  augmenting events with schema info
 *      3. sending of events to applier.
 * </p>
 */
public class PipelineOrchestrator extends Thread {
    private static final Logger LOGGER = LoggerFactory.getLogger(PipelineOrchestrator.class);
    private static final Meter eventsReceivedCounter = Metrics.registry.meter(name("events", "eventsReceivedCounter"));
    private static final Meter eventsProcessedCounter = Metrics.registry.meter(name("events", "eventsProcessedCounter"));
    private static final Meter eventsSkippedCounter = Metrics.registry.meter(name("events", "eventsSkippedCounter"));
    private static final Meter eventsRewindedCounter = Metrics.registry.meter(name("events", "eventsRewindedCounter"));

//<<<<<<< HEAD
    private static final int BUFFER_FLUSH_INTERVAL = 30000; // <- force buffer flush every 30 sec
    private static final int DEFAULT_VERSIONS_FOR_MIRRORED_TABLES = 1000;
    private static final long QUEUE_POLL_TIMEOUT = 100L;
    private static final long QUEUE_POLL_SLEEP = 500;
    private static EventAugmenter eventAugmenter;
    private static ActiveSchemaVersion activeSchemaVersion;
//=======
//    public  final  Configuration                   configuration;
//    private final  ReplicantPool                   replicantPool;
//    private final  Applier                         applier;
//    private final  BlockingQueue<RawBinlogEvent>   rawBinlogEventQueue;
//    private final  QueryInspector                  queryInspector;
//    private static EventAugmenter                  eventAugmenter;
//    private static ActiveSchemaVersion             activeSchemaVersion;
//>>>>>>> Migrating to binlog connector. Temporarily will support both parsers.
    private static LastCommittedPositionCheckpoint lastVerifiedPseudoGTIDCheckPoint;
    public final Configuration configuration;
    private final Configuration.OrchestratorConfiguration orchestratorConfiguration;
    private final ReplicantPool replicantPool;
    private final Applier applier;
    private final ReplicatorQueues queues;
    private final EventDispatcher eventDispatcher = new EventDispatcher();

    private final PipelinePosition pipelinePosition;
    private final BinlogEventProducer binlogEventProducer;
    public CurrentTransaction currentTransaction = null;

    private volatile boolean running = false;
    private volatile boolean replicatorShutdownRequested = false;


    private HashMap<String, Boolean> rotateEventAllreadySeenForBinlogFile = new HashMap<>();

    /**
     * Fake microsecond counter.
     * <p>
     * <p>This is a special feature that
     * requires some explanation</p>
     * <p>
     * <p>MySQL binlog events have second-precision timestamps. This
     * obviously means that we can't have microsecond precision,
     * but that is not the intention here. The idea is to at least
     * preserve the information about ordering of events,
     * especially if one ID has multiple events within the same
     * second. We want to know what was their order. That is the
     * main purpose of this counter.</p>
     */
    private long fakeMicrosecondCounter = 0L;
    private long previousTimestamp = 0L;
    private long timeOfLastEvent = 0L;
    private boolean isRewinding = false;

    private Long replDelay = 0L;



    public PipelineOrchestrator(
            LinkedBlockingQueue<RawBinlogEvent> rawBinlogEventQueue,
            PipelinePosition pipelinePosition,
            Configuration repcfg,
            Applier applier,
            ReplicantPool replicantPool,
            BinlogEventProducer binlogEventProducer,
            long fakeMicrosecondCounter,
            boolean metricsEnabled) throws SQLException, URISyntaxException {

        this.rawBinlogEventQueue = rawBinlogEventQueue;
        configuration = repcfg;
        orchestratorConfiguration = configuration.getOrchestratorConfiguration();

        this.replicantPool = replicantPool;
        this.fakeMicrosecondCounter = fakeMicrosecondCounter;
        this.binlogEventProducer = binlogEventProducer;

        eventAugmenter = new EventAugmenter(activeSchemaVersion, configuration.getAugmenterApplyUuid(), configuration.getAugmenterApplyXid());

        this.applier = applier;

        LOGGER.info("Created consumer with binlog position => { "
                + " binlogFileName => "
                + pipelinePosition.getCurrentPosition().getBinlogFilename()
                + ", binlogPosition => "
                + pipelinePosition.getCurrentPosition().getBinlogPosition()
                + " }"
        );

        if (metricsEnabled) registerMetrics();

        this.pipelinePosition = pipelinePosition;

        initEventDispatcher();
    }

    public PipelineOrchestrator(
            ReplicatorQueues repQueues,
            PipelinePosition pipelinePosition,
            Configuration repcfg,
            Applier applier,
            ReplicantPool replicantPool,
            BinlogEventProducer binlogEventProducer,
            long fakeMicrosecondCounter) throws SQLException, URISyntaxException {
        this(repQueues, pipelinePosition, repcfg, applier, replicantPool, binlogEventProducer, fakeMicrosecondCounter, true);
    }

    private void registerMetrics() {
        Metrics.registry.register(MetricRegistry.name("events", "replicatorReplicationDelay"),
                (Gauge<Long>) () -> replDelay);
    }


    public static void setActiveSchemaVersion(ActiveSchemaVersion activeSchemaVersion) {
        PipelineOrchestrator.activeSchemaVersion = activeSchemaVersion;
    }

    private void initEventDispatcher() {
        EventHandlerConfiguration eventHandlerConfiguration = new EventHandlerConfiguration(applier, eventAugmenter, this);

        eventDispatcher.registerHandler(
                MySQLConstants.QUERY_EVENT,
                new QueryEventHandler(eventHandlerConfiguration, activeSchemaVersion, pipelinePosition));

        eventDispatcher.registerHandler(
                MySQLConstants.TABLE_MAP_EVENT,
                new TableMapEventHandler(eventHandlerConfiguration, pipelinePosition, replicantPool));

        eventDispatcher.registerHandler(Arrays.asList(
                MySQLConstants.UPDATE_ROWS_EVENT, MySQLConstants.UPDATE_ROWS_EVENT_V2),
                new UpdateRowsEventHandler(eventHandlerConfiguration));

        eventDispatcher.registerHandler(Arrays.asList(
                MySQLConstants.WRITE_ROWS_EVENT, MySQLConstants.WRITE_ROWS_EVENT_V2),
                new WriteRowsEventHandler(eventHandlerConfiguration));

        eventDispatcher.registerHandler(Arrays.asList(
                MySQLConstants.DELETE_ROWS_EVENT, MySQLConstants.DELETE_ROWS_EVENT_V2),
                new DeleteRowsEventHandler(eventHandlerConfiguration));

        eventDispatcher.registerHandler(
                MySQLConstants.XID_EVENT,
                new XidEventHandler(eventHandlerConfiguration));

        eventDispatcher.registerHandler(
                MySQLConstants.FORMAT_DESCRIPTION_EVENT,
                new FormatDescriptionEventHandler(eventHandlerConfiguration));

        eventDispatcher.registerHandler(
                MySQLConstants.ROTATE_EVENT,
                new RotateEventHandler(eventHandlerConfiguration, pipelinePosition, configuration.getLastBinlogFileName()));

        eventDispatcher.registerHandler(
                MySQLConstants.STOP_EVENT,
                new DummyEventHandler());
    }

    public long getFakeMicrosecondCounter() {
        return fakeMicrosecondCounter;
    }

    public boolean isRunning() {
        return running;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public void requestReplicatorShutdown() {
        replicatorShutdownRequested = true;
    }

    public void requestShutdown() {
        setRunning(false);
        requestReplicatorShutdown();
    }

    public boolean isReplicatorShutdownRequested() {
        return replicatorShutdownRequested;
    }

    @Override
    public void run() {

        setRunning(true);
        timeOfLastEvent = System.currentTimeMillis();
        try {
            // block in a loop
            processQueueLoop();

        } catch (SchemaTransitionException e) {
            LOGGER.error("SchemaTransitionException, requesting replicator shutdown...", e);
            requestReplicatorShutdown();
        } catch (InterruptedException e) {
            LOGGER.error("InterruptedException, requesting replicator shutdown...", e);
            requestReplicatorShutdown();
        } catch (TableMapException e) {
            LOGGER.error("TableMapException, requesting replicator shutdown...", e);
            requestReplicatorShutdown();
        } catch (IOException e) {
            LOGGER.error("IOException, requesting replicator shutdown...", e);
            requestReplicatorShutdown();
        } catch (Exception e) {
            LOGGER.error("Exception, requesting replicator shutdown...", e);
            requestReplicatorShutdown();
        }
    }


    private void processQueueLoop() throws Exception {
        processQueueLoop(null);
    }

    private void processQueueLoop(BinlogPositionInfo exitOnBinlogPosition) throws Exception {
        while (isRunning()) {
//<<<<<<< HEAD
            BinlogEventV4 event = waitForEvent(QUEUE_POLL_TIMEOUT, QUEUE_POLL_SLEEP);
            LOGGER.debug("Received event: " + event);

            timeOfLastEvent = System.currentTimeMillis();
            eventsReceivedCounter.mark();

            // Update pipeline position
            fakeMicrosecondCounter++;

            BinlogPositionInfo currentPosition = new BinlogPositionInfo(replicantPool.getReplicantDBActiveHost(),
                    replicantPool.getReplicantDBActiveHostServerID(), EventPosition.getEventBinlogFileName(event),
                    EventPosition.getEventBinlogPosition(event), fakeMicrosecondCounter);
            pipelinePosition.setCurrentPosition(currentPosition);

            processEvent(event);

            if (exitOnBinlogPosition != null && currentPosition.compareTo(exitOnBinlogPosition) == 0) {
                LOGGER.debug("currentTransaction: " + currentTransaction);
                break;
            }
            if (currentTransaction != null && currentTransaction.isRewinded()) {
                currentTransaction.setEventsTimestampToFinishEvent();
                applyTransactionDataEvents();
            }
        }
    }

    private void processEvent(BinlogEventV4 event) throws Exception {
        if (skipEvent(event)) {
            LOGGER.debug("Skipping event: " + event);
            eventsSkippedCounter.mark();
        } else {
            LOGGER.debug("Processing event: " + event);
            calculateAndPropagateChanges(event);
            eventsProcessedCounter.mark();
        }
    }

    private BinlogEventV4 rewindToCommitEvent() throws ApplierException, IOException, InterruptedException {
        return rewindToCommitEvent(QUEUE_POLL_TIMEOUT, QUEUE_POLL_SLEEP);
    }

    private BinlogEventV4 rewindToCommitEvent(long timeout, long sleep) throws ApplierException, IOException, InterruptedException {
        LOGGER.debug("Rewinding to the next commit event. Either XidEvent or QueryEvent with COMMIT statement");
        BinlogEventV4 resultEvent = null;
        while (isRunning() && resultEvent == null) {
            BinlogEventV4 event = waitForEvent(timeout, sleep);
            if (event == null) continue;

            eventsRewindedCounter.mark();

            moveFakeMicrosecondCounter(event.getHeader().getTimestamp());

            switch (event.getHeader().getEventType()) {
                case MySQLConstants.XID_EVENT:
                    resultEvent = event;
                    break;
                case MySQLConstants.QUERY_EVENT:
                    if (QueryInspector.getQueryEventType((QueryEvent) event).equals(QueryEventType.COMMIT)) {
                        resultEvent = event;
                        break;
//=======
//            try {
//
//                if (rawBinlogEventQueue.size() > 0) {
//
//                    RawBinlogEvent rawBinlogEvent = this.rawBinlogEventQueue.poll(100, TimeUnit.MILLISECONDS);
//
//                    if (rawBinlogEvent == null) {
//                        LOGGER.warn("Poll timeout. Will sleep for 1s and try again.");
//                        Thread.sleep(1000);
//                        continue;
//                    }
//
//                    timeOfLastEvent = System.currentTimeMillis();
//                    eventsReceivedCounter.mark();

//                    // Update pipeline position
//                    fakeMicrosecondCounter++;
//                    pipelinePosition.updatCurrentPipelinePosition(
//                        replicantPool.getReplicantDBActiveHost(),
//                        replicantPool.getReplicantDBActiveHostServerID(),
//                        rawBinlogEvent,
//                        fakeMicrosecondCounter
//                    );
//
//                    if (! skipEvent(rawBinlogEvent)) {
//                        calculateAndPropagateChanges(rawBinlogEvent);
//                        eventsProcessedCounter.mark();
//                    } else {
//                        eventsSkippedCounter.mark();
//                    }
//                } else {
//                    LOGGER.debug("Pipeline report: no items in producer event rawQueue. Will sleep for 0.5s and check again.");
//                    Thread.sleep(500);
//                    long currentTime = System.currentTimeMillis();
//                    long timeDiff = currentTime - timeOfLastEvent;
//                    boolean forceFlush = (timeDiff > BUFFER_FLUSH_INTERVAL);
//                    if (forceFlush) {
//                        applier.forceFlush();
//>>>>>>> Migrating to binlog connector. Temporarily will support both parsers.
                    }
                default:
                    LOGGER.debug("Skipping event due to rewinding: " + event);
                    break;
            }
        }
        LOGGER.debug("Rewinded to the position: " + EventPosition.getEventBinlogFileNameAndPosition(resultEvent) + ", event: " + resultEvent);
        doTimestampOverride(resultEvent);
        return resultEvent;
    }

    private BinlogEventV4 waitForEvent(long timeout, long sleep) throws InterruptedException, ApplierException, IOException {
        while (isRunning()) {
            if (queues.rawQueue.size() > 0) {
                BinlogEventV4 event = queues.rawQueue.poll(timeout, TimeUnit.MILLISECONDS);

                if (event == null) {
                    LOGGER.warn("Poll timeout. Will sleep for " + QUEUE_POLL_SLEEP * 2  + "ms and try again.");
                    Thread.sleep(sleep * 2);
                    continue;
                }
                return event;

            } else {
                LOGGER.debug("Pipeline report: no items in producer event rawQueue. Will sleep for " + QUEUE_POLL_SLEEP + " and check again.");
                Thread.sleep(sleep);
                long currentTime = System.currentTimeMillis();
                long timeDiff = currentTime - timeOfLastEvent;
                boolean forceFlush = (timeDiff > BUFFER_FLUSH_INTERVAL);
                if (forceFlush) {
                    applier.forceFlush();
                }
            }
        }
        return null;
    }

    private void moveFakeMicrosecondCounter(long timestamp) {

        if (fakeMicrosecondCounter > 999998L) {
            fakeMicrosecondCounter = 0L;
            LOGGER.warn("Fake microsecond counter's overflowed, resetting to 0. It might lead to incorrect events order.");
        }

        if (timestamp > previousTimestamp) {
            fakeMicrosecondCounter = 0L;
            previousTimestamp = timestamp;
        }
    }

    /**
     *  Calculate and propagate changes.
     *
     *  <p>STEPS:
     *     ======
     *  1. check event type
     *
     *  2. if DDL:
     *      a. pass to eventAugmenter which will update the schema
     *
     *  3. if DATA:
     *      a. match column names and types
     * </p>
     */
//<<<<<<< HEAD
    private void calculateAndPropagateChanges(BinlogEventV4 event) throws Exception {
//=======
//    public void calculateAndPropagateChanges(RawBinlogEvent event)
//            throws Exception, TableMapException {
//
//        AugmentedRowsEvent augmentedRowsEvent;
//
//        if (fakeMicrosecondCounter > 999998) {
//            fakeMicrosecondCounter = 0;
//        }
//>>>>>>> Migrating to binlog connector. Temporarily will support both parsers.

        // Calculate replication delay before the event timestamp is extended with fake miscrosecond part
        // Note: there is a bug in open replicator which results in rotate event having timestamp value = 0.
        //       This messes up the replication delay time series. The workaround is not to calculate the
        //       replication delay at rotate event.
        if (event.hasHeader()) {
            if ((event.getTimestampOfReceipt() > 0)
                    && (event.getTimestamp() > 0) ) {
                replDelay = event.getTimestampOfReceipt() - event.getTimestamp();
            } else {
                if (event.isRotate()) {
                    // do nothing, expected for rotate event
                } else {
                    // warn, not expected for other events
                    LOGGER.warn("Invalid timestamp value for event " + event.toString());
                }
            }
        } else {
            LOGGER.error("Event header can not be null. Shutting down...");
            requestReplicatorShutdown();
        }

        // check if the applier commit stream moved to a new check point. If so,
        // store the the new safe check point; currently only supported for hbase applier
        if (applier instanceof HBaseApplier) {
            LastCommittedPositionCheckpoint lastCommittedPseudoGTIDReportedByApplier =
                ((HBaseApplier) applier).getLastCommittedPseudGTIDCheckPoint();

            if (lastVerifiedPseudoGTIDCheckPoint == null
                    && lastCommittedPseudoGTIDReportedByApplier != null) {
                lastVerifiedPseudoGTIDCheckPoint = lastCommittedPseudoGTIDReportedByApplier;
                LOGGER.info("Save new marker: " + lastVerifiedPseudoGTIDCheckPoint.toJson());
                Coordinator.saveCheckpointMarker(lastVerifiedPseudoGTIDCheckPoint);
            } else if (lastVerifiedPseudoGTIDCheckPoint != null
                    && lastCommittedPseudoGTIDReportedByApplier != null) {
                if (!lastVerifiedPseudoGTIDCheckPoint.getPseudoGTID().equals(
                        lastCommittedPseudoGTIDReportedByApplier.getPseudoGTID())) {
                    LOGGER.info("Reached new safe checkpoint " + lastCommittedPseudoGTIDReportedByApplier.getPseudoGTID() );
                    lastVerifiedPseudoGTIDCheckPoint = lastCommittedPseudoGTIDReportedByApplier;
                    LOGGER.info("Save new marker: " + lastVerifiedPseudoGTIDCheckPoint.toJson());
                    Coordinator.saveCheckpointMarker(lastVerifiedPseudoGTIDCheckPoint);
                }
            }
        }

//<<<<<<< HEAD
        moveFakeMicrosecondCounter(event.getHeader().getTimestamp());
        doTimestampOverride(event);

        // Process Event
        try {
            eventDispatcher.handle(event);
        } catch (TransactionSizeLimitException e) {
            LOGGER.info("Transaction size limit(" + orchestratorConfiguration.getRewindingThreshold() + ") exceeded. Applying with rewinding uuid: " + currentTransaction.getUuid());
            applyTransactionWithRewinding();
        } catch (TransactionException e) {
            LOGGER.error("EventManger failed to handle event: ", e);
            requestShutdown();
//=======
//        // Process Event
//        RawEventType rawEventType = event.getEventType();
//        switch (rawEventType) {
//
//            // Check for DDL and pGTID:
//            case QUERY_EVENT:
//                doTimestampOverride(event);
//               String querySQL = event.getQuerySQL();
//
//               boolean isPseudoGTID = queryInspector.isPseudoGTID(querySQL);
//               if (isPseudoGTID) {
//                   pgtidCounter.mark();
//                   // Events in the same second after pGTID can be written to HBase twice
 //                   // with different timestamp-microsecond-part during failover, resulting
 //                   // in duplicate entries in HBase for that second. By reseting the 
 //                   // microsecond part on pGTID event this posibility is removed, and we have
 //                   // exactly-once-delivery even during failover.
 //                   fakeMicrosecondCounter = 0;
 //                   try {
 //                       String pseudoGTID = queryInspector.extractPseudoGTID(querySQL);
 //                       pipelinePosition.setCurrentPseudoGTID(pseudoGTID);
 //                       pipelinePosition.setCurrentPseudoGTIDFullQuery(querySQL);
 //                       if (applier instanceof  HBaseApplier) {
 //                           try {
 //                               ((HBaseApplier) applier).applyPseudoGTIDEvent(new LastCommittedPositionCheckpoint(
 //                                   pipelinePosition.getCurrentPosition().getHost(),
 //                                   pipelinePosition.getCurrentPosition().getServerID(),
 //                                   pipelinePosition.getCurrentPosition().getBinlogFilename(),
 //                                   pipelinePosition.getCurrentPosition().getBinlogPosition(),
 //                                   pseudoGTID,
 //                                   querySQL
 //                               ));
 //                           } catch (TaskBufferInconsistencyException e) {
 //                               e.printStackTrace();
 //                           }
 //                       }
 //                   } catch (QueryInspectorException e) {
 //                       LOGGER.error("Failed to update pipelinePosition with new pGTID!", e);
 //                       setRunning(false);
 //                       requestReplicatorShutdown();
 //                   }
 //               }
 //
 //               boolean isDDLTable = queryInspector.isDDLTable(querySQL);
 //               boolean isDDLView = queryInspector.isDDLView(querySQL);
 //
 //               if (queryInspector.isCommit(querySQL, isDDLTable)) {
 //                   commitQueryCounter.mark();
 //                   applier.applyCommitQueryEvent();
 //               } else if (queryInspector.isBegin(querySQL, isDDLTable)) {
 //                   currentTransactionMetadata = new CurrentTransactionMetadata();
 //               } else if (isDDLTable) {
 //                   // Sync all the things here.
 //                   applier.forceFlush();
 //                   applier.waitUntilAllRowsAreCommitted();
 //
 //                   try {
 //                       AugmentedSchemaChangeEvent augmentedSchemaChangeEvent = activeSchemaVersion.transitionSchemaToNextVersion(
 //                               eventAugmenter.getSchemaTransitionSequence(event),
 //                               event.getTimestamp()
 //                       );
 //
 //                       String currentBinlogFileName =
 //                               pipelinePosition.getCurrentPosition().getBinlogFilename();
 //
 //                       long currentBinlogPosition = event.getPosition();
 //
 //                       String pseudoGTID          = pipelinePosition.getCurrentPseudoGTID();
 //                       String pseudoGTIDFullQuery = pipelinePosition.getCurrentPseudoGTIDFullQuery();
 //                       int currentSlaveId         = pipelinePosition.getCurrentPosition().getServerID();
 //
 //                       LastCommittedPositionCheckpoint marker = new LastCommittedPositionCheckpoint(
 //                               pipelinePosition.getCurrentPosition().getHost(),
 //                               currentSlaveId,
 //                               currentBinlogFileName,
 //                               currentBinlogPosition,
 //                               pseudoGTID,
 //                               pseudoGTIDFullQuery
 //                       );
 //
 //                       LOGGER.info("Save new marker: " + marker.toJson());
 //                       Coordinator.saveCheckpointMarker(marker);
 //                       applier.applyAugmentedSchemaChangeEvent(augmentedSchemaChangeEvent, this);
 //                   } catch (SchemaTransitionException e) {
 //                       setRunning(false);
 //                       requestReplicatorShutdown();
 //                       throw e;
 //                   } catch (Exception e) {
 //                       LOGGER.error("Failed to save checkpoint marker!");
 //                       e.printStackTrace();
 //                       setRunning(false);
 //                       requestReplicatorShutdown();
 //                   }
 //               } else if (isDDLView) {
 //                   // TODO: add view schema changes to view schema history
 //               } else {
 //                   LOGGER.warn("Unexpected query event: " + querySQL);
 //               }
 //               break;
 //
 //           // TableMap event:
 //           case TABLE_MAP_EVENT:
 //               String tableName = ((RawBinlogEventTableMap) event).getTableName();
 //
 //               if (tableName.equals(Constants.HEART_BEAT_TABLE)) {
 //                   // reset the fake microsecond counter on hearth beat event. In our case
 //                   // hearth-beat is a regular update and it is treated as such in the rest
 //                   // of the code (therefore replicated in HBase table so we have the
 //                   // hearth-beat in HBase and can use it to check replication delay). The only
 //                   // exception is that when we see this event we reset the fake-microseconds counter.
 //                   LOGGER.debug("fakeMicrosecondCounter before reset => " + fakeMicrosecondCounter);
 //                   fakeMicrosecondCounter = 0;
 //                   doTimestampOverride(event);
 //                   heartBeatCounter.mark();
 //               } else {
 //                   doTimestampOverride(event);
 //               }
//
//                try {
//
//                    currentTransactionMetadata.updateCache((RawBinlogEventTableMap) event);
//
//                    long tableID = ((RawBinlogEventTableMap) event).getTableId();
//                    String dbName = currentTransactionMetadata.getDBNameFromTableID(tableID);
//                    LOGGER.debug("processing events for { db => " + dbName + " table => " + ((RawBinlogEventTableMap) event).getTableName() + " } ");
//                    LOGGER.debug("fakeMicrosecondCounter at tableMap event => " + fakeMicrosecondCounter);
//
//                    applier.applyTableMapEvent((RawBinlogEventTableMap) event);
//
//                    this.pipelinePosition.updatePipelineLastMapEventPosition(
 //                       replicantPool.getReplicantDBActiveHost(),
 //                       replicantPool.getReplicantDBActiveHostServerID(),
 //                       (RawBinlogEventTableMap) event,
 //                       fakeMicrosecondCounter
 //                   );
 //
 //               } catch (Exception e) {
 //                   LOGGER.error("Could not execute mapEvent block. Requesting replicator shutdown...", e);
 //                   requestReplicatorShutdown();
 //               }
 //
 //               break;
 //
  //          // Data event:
  //          case UPDATE_ROWS_EVENT:
  //              doTimestampOverride(event);
  //              augmentedRowsEvent = eventAugmenter.mapDataEventToSchema((RawBinlogEventRows) event, this);
  //              applier.applyAugmentedRowsEvent(augmentedRowsEvent,this);
  //              updateEventCounter.mark();
  //              break;
  //
  //          case WRITE_ROWS_EVENT:
  //              doTimestampOverride(event);
  //              augmentedRowsEvent = eventAugmenter.mapDataEventToSchema((RawBinlogEventRows) event, this);
  //              applier.applyAugmentedRowsEvent(augmentedRowsEvent,this);
  //              insertEventCounter.mark();
 //               break;
 //
 //           case DELETE_ROWS_EVENT:
 //               doTimestampOverride(event);
 //               augmentedRowsEvent = eventAugmenter.mapDataEventToSchema((RawBinlogEventRows) event, this);
 //               applier.applyAugmentedRowsEvent(augmentedRowsEvent,this);
 //               deleteEventCounter.mark();
 //               break;
 //
 //           case XID_EVENT:
 //               // Later we may want to tag previous data events with xid_id
 //               // (so we can know if events were in the same transaction).
 //               doTimestampOverride(event);
 //               applier.applyXidEvent((RawBinlogEventXid) event);
 //               XIDCounter.mark();
 //               currentTransactionMetadata = new CurrentTransactionMetadata();
 //               break;
 //
 //           // reset the fakeMicrosecondCounter at the beginning of the new binlog file
 //           case FORMAT_DESCRIPTION_EVENT:
 //               fakeMicrosecondCounter = 0;
 //               applier.applyFormatDescriptionEvent((RawBinlogEventFormatDescription) event);
 //               break;
 //
 //           // flush buffer at the end of binlog file
 //           case ROTATE_EVENT:
 //               RawBinlogEventRotate rotateEvent = (RawBinlogEventRotate) event;
 //               applier.applyRotateEvent(rotateEvent);
 //               LOGGER.info("End of binlog file. Waiting for all tasks to finish before moving forward...");
 //
 //               //TODO: Investigate if this is the right thing to do.
 //               applier.waitUntilAllRowsAreCommitted();
 //
 //               String currentBinlogFileName =
 //                       pipelinePosition.getCurrentPosition().getBinlogFilename();
 //
 //               String nextBinlogFileName = rotateEvent.getBinlogFileName().toString();
 //               long currentBinlogPosition = rotateEvent.getPosition();
 //
 //               LOGGER.info("All rows committed for binlog file "
 //                       + currentBinlogFileName + ", moving to next binlog " + nextBinlogFileName);
 //
 //               String pseudoGTID          = pipelinePosition.getCurrentPseudoGTID();
 //               String pseudoGTIDFullQuery = pipelinePosition.getCurrentPseudoGTIDFullQuery();
 //               int currentSlaveId         = pipelinePosition.getCurrentPosition().getServerID();
 //
 //               LastCommittedPositionCheckpoint marker = new LastCommittedPositionCheckpoint(
 //                       pipelinePosition.getCurrentPosition().getHost(),
 //                       currentSlaveId,
 //                       nextBinlogFileName,
 //                       currentBinlogPosition,
 //                       pseudoGTID,
 //                       pseudoGTIDFullQuery
 //               );
//
//                try {
//                    Coordinator.saveCheckpointMarker(marker);
//                } catch (Exception e) {
//                    LOGGER.error("Failed to save Checkpoint!");
//                    e.printStackTrace();
//                }
//
 //               if (currentBinlogFileName.equals(configuration.getLastBinlogFileName())) {
 //                   LOGGER.info("processed the last binlog file " + configuration.getLastBinlogFileName());
 //                   setRunning(false);
 //                   requestReplicatorShutdown();
 //               }
 //               break;
//
 //           // Events that we expect to appear in the binlog, but we don't do
 //           // any extra processing.
 //           case STOP_EVENT:
 //               break;
 //
//            // Events that we do not expect to appear in the binlog
//            // so a warning should be logged for those types
//            default:
//                LOGGER.warn("Unexpected event type: " + rawEventType);
//                break;
//>>>>>>> Migrating to binlog connector. Temporarily will support both parsers.
        }
    }

    private boolean isReplicant(String schemaName) {
        return schemaName.equals(configuration.getReplicantSchemaName());
    }

    /**
     * Returns true if event type is not tracked, or does not belong to the
     * tracked database.
     *
     * @param  event Binlog event that needs to be checked
     * @return shouldSkip Weather event should be skipped or processed
     */
<<<<<<< HEAD
    private boolean skipEvent(BinlogEventV4 event) throws Exception {
=======
    public boolean skipEvent(RawBinlogEvent event) throws Exception {
        boolean eventIsTracked      = false;
        boolean skipEvent;

>>>>>>> Migrating to binlog connector. Temporarily will support both parsers.
        // if there is a last safe checkpoint, skip events that are before
        // or equal to it, so that the same events are not writen multiple
        // times (beside wasting IO, this would fail the DDL operations,
        // for example trying to create a table that allready exists)
        if (pipelinePosition.getLastSafeCheckPointPosition() != null) {
            if ((pipelinePosition.getLastSafeCheckPointPosition().greaterThan(pipelinePosition.getCurrentPosition()))
                    || (pipelinePosition.getLastSafeCheckPointPosition().equals(pipelinePosition.getCurrentPosition()))) {
                LOGGER.info("Event position { binlog-filename => "
                        + pipelinePosition.getCurrentPosition().getBinlogFilename()
                        + ", binlog-position => "
                        + pipelinePosition.getCurrentPosition().getBinlogPosition()
                        + " } is lower or equal then last safe checkpoint position { "
                        + " binlog-filename => "
                        + pipelinePosition.getLastSafeCheckPointPosition().getBinlogFilename()
                        + ", binlog-position => "
                        + pipelinePosition.getLastSafeCheckPointPosition().getBinlogPosition()
                        + " }. Skipping event...");
                return true;
            }
        }

        RawEventType rawEventType = event.getEventType();
        switch (rawEventType) {
            // Query Event:
            case QUERY_EVENT:

// <<<<<<< HEAD
                switch (QueryInspector.getQueryEventType((QueryEvent) event)) {
                    case BEGIN:
                    case PSEUDOGTID:
                        return false;
                    case COMMIT:
                        // COMMIT does not always contain database name so we get it
                        // from current transaction metadata.
                        // There is an assumption that all tables in the transaction
                        // are from the same database. Cross database transactions
                        // are not supported.
                        LOGGER.debug("Got commit event: " + event);
                        TableMapEvent firstMapEvent = currentTransaction.getFirstMapEventInTransaction();
                        if (firstMapEvent == null) {
                            LOGGER.warn(String.format(
                                    "Received COMMIT event, but currentTransaction is empty! Tables in transaction are %s",
                                    Joiner.on(", ").join(currentTransaction.getCurrentTransactionTableMapEvents().keySet())
                                    )
                            );
                            dropTransaction();
                            return true;
                            //throw new TransactionException("Got COMMIT while not in transaction: " + currentTransaction);
                        }

//=======
//                String querySQL = ((RawBinlogEventQuery) event).getSql();
//
//                boolean isDDLTable   = queryInspector.isDDLTable(querySQL);
//                boolean isCommit     = queryInspector.isCommit(querySQL, isDDLTable);
//                boolean isBegin      = queryInspector.isBegin(querySQL, isDDLTable);
//               boolean isPseudoGTID = queryInspector.isPseudoGTID(querySQL);
//
//               if (isPseudoGTID) {
//                   skipEvent = false;
//                   return skipEvent;
//               }
//
//                if (isCommit) {
//                    // COMMIT does not always contain database name so we get it
//                    // from current transaction metadata.
//                    // There is an assumption that all tables in the transaction
//                    // are from the same database. Cross database transactions
//                    // are not supported.
//                    RawBinlogEventTableMap firstMapEvent = currentTransactionMetadata.getFirstMapEventInTransaction();
//                    if (firstMapEvent != null) {
//>>>>>>> Migrating to binlog connector. Temporarily will support both parsers.
                        String currentTransactionDBName = firstMapEvent.getDatabaseName().toString();
                        if (!isReplicant(currentTransactionDBName)) {
                            LOGGER.warn(String.format("non-replicated database %s in current transaction.",
                                    currentTransactionDBName));
                            dropTransaction();
                            return true;
                        }
//<<<<<<< HEAD

                        return false;
                    case DDLTABLE:
                        // DDL event should always contain db name
                        String dbName = ((QueryEvent) event).getDatabaseName().toString();
                        if (dbName.length() == 0) {
                            LOGGER.warn("No Db name in Query Event. Extracted SQL: " + ((QueryEvent) event).getSql().toString());
                        }
                        if (isReplicant(dbName)) {
                            // process event
                            return false;
                        }
                        // skip event
                        LOGGER.warn("DDL statement " + ((QueryEvent) event).getSql() + " on non-replicated database: " + dbName + "");
                        return true;
                    case DDLVIEW:
                        // TODO: handle View statement
                        return true;
                    case ANALYZE:
                        return true;
                    default:
                        LOGGER.warn("Skipping event with unknown query type: " + ((QueryEvent) event).getSql());
                        return false;
//=======
//                    } else {
//                        LOGGER.warn(String.format(
//                                "Received COMMIT event, but currentTransactionMetadata is empty! Tables in transaction are %s",
//                                Joiner.on(", ").join(currentTransactionMetadata.getCurrentTransactionTableMapEvents().keySet())
//                            )
//                        );
//                    }
//                } else if (isBegin) {
//                    eventIsTracked = true;
//                } else if (isDDLTable) {
//                   // DDL event should always contain db name
//                   String dbName = ((RawBinlogEventQuery) event).getDatabaseName();
//                   if ((dbName == null) || dbName.length() == 0) {
//                       LOGGER.warn("No Db name in Query Event. Extracted SQL: " + ((RawBinlogEventQuery) event).getSql());
//                   }
//                   if (isReplicant(dbName)) {
//                       eventIsTracked = true;
//                   } else {
//                       eventIsTracked = false;
//                       LOGGER.warn("DDL statement " + querySQL + " on non-replicated database: " + dbName + "");
//                   }
//                } else {
//                    // TODO: handle View statement
//                    // LOGGER.warn("Received non-DDL, non-COMMIT, non-BEGIN query: " + querySQL);
//>>>>>>> Migrating to binlog connector. Temporarily will support both parsers.
                }

            // TableMap event:
//<<<<<<< HEAD
            case MySQLConstants.TABLE_MAP_EVENT:
                return !isReplicant(((TableMapEvent) event).getDatabaseName().toString());
            // Data event:
            case MySQLConstants.UPDATE_ROWS_EVENT:
            case MySQLConstants.UPDATE_ROWS_EVENT_V2:
            case MySQLConstants.WRITE_ROWS_EVENT:
            case MySQLConstants.WRITE_ROWS_EVENT_V2:
            case MySQLConstants.DELETE_ROWS_EVENT:
            case MySQLConstants.DELETE_ROWS_EVENT_V2:
                return currentTransaction.getFirstMapEventInTransaction() == null;
            case MySQLConstants.XID_EVENT:
                return false;
//=======
//            case TABLE_MAP_EVENT:
//                eventIsTracked = isReplicant(((RawBinlogEventTableMap) event).getDatabaseName());
//                break;
//
//            // Data event:
//            case UPDATE_ROWS_EVENT:
//            case WRITE_ROWS_EVENT:
//            case DELETE_ROWS_EVENT:
//                eventIsTracked = currentTransactionMetadata.getFirstMapEventInTransaction() != null;
//                break;
//
//            case XID_EVENT:
//                eventIsTracked = currentTransactionMetadata.getFirstMapEventInTransaction() != null;
//                break;
//>>>>>>> Migrating to binlog connector. Temporarily will support both parsers.

            case ROTATE_EVENT:
                // This is a  workaround for a bug in open replicator
                // which results in rotate event being created twice per
                // binlog file - once at the end of the binlog file (as it should be)
                // and once at the beginning of the next binlog file (which is a bug)
                String currentBinlogFile =
                        pipelinePosition.getCurrentPosition().getBinlogFilename();
                if (rotateEventAllreadySeenForBinlogFile.containsKey(currentBinlogFile)) {
                    return true;
                }
//<<<<<<< HEAD
                rotateEventAllreadySeenForBinlogFile.put(currentBinlogFile, true);
                return false;
            case MySQLConstants.FORMAT_DESCRIPTION_EVENT:
            case MySQLConstants.STOP_EVENT:
                return false;
            default:
                LOGGER.warn("Unexpected event type => " + event.getHeader().getEventType());
                return true;
        }
    }

    public boolean beginTransaction() {
        // a manual transaction beginning
        if (currentTransaction != null) {
            return false;
        }
        currentTransaction = new CurrentTransaction();
        LOGGER.debug("Started transaction " + currentTransaction.getUuid() + " without event");
        return true;
    }

    public boolean beginTransaction(QueryEvent event) {
        // begin a transaction with BEGIN query event
        if (currentTransaction != null) {
            return false;
        }
        currentTransaction = new CurrentTransaction(event);
        LOGGER.debug("Started transaction " + currentTransaction.getUuid() + " with event: " + event);
        return true;
    }

    public void addEventIntoTransaction(BinlogEventV4 event) throws TransactionException, TransactionSizeLimitException {
        if (!isInTransaction()) {
            throw new TransactionException("Failed to add new event into a transaction buffer while not in transaction: " + event);
        }
        if (isTransactionSizeLimitExceeded()) {
            throw new TransactionSizeLimitException();
        }
        currentTransaction.addEvent(event);
        if (currentTransaction.getEventsCounter() % 10 == 0) {
            LOGGER.debug("Number of events in current transaction " + currentTransaction.getUuid() + " is: " + currentTransaction.getEventsCounter());
        }
    }

    private void applyTransactionWithRewinding() throws Exception {
        LOGGER.debug("Applying transaction with rewinding");
        if (isRewinding) {
            throw new RuntimeException("Recursive rewinding detected. CurrentTransaction:" + currentTransaction);
        }
        QueryEvent beginEvent = currentTransaction.getBeginEvent();
        LOGGER.debug("Start rewinding transaction from: " + EventPosition.getEventBinlogFileNameAndPosition(beginEvent));

        isRewinding = true;
        currentTransaction.setRewinded(true);

        // drop data events from current transaction
        currentTransaction.clearEvents();

        // get next xid event and skip everything before
        BinlogEventV4 commitEvent = rewindToCommitEvent();
        if (commitEvent.getHeader().getEventType() == MySQLConstants.XID_EVENT) {
            currentTransaction.setFinishEvent((XidEvent) commitEvent);
        } else {
            currentTransaction.setFinishEvent((QueryEvent) commitEvent);
        }

        // set binlog pos to begin pos, start openReplicator and apply the xid data to all events
        try {
            binlogEventProducer.stopAndClearQueue(10000, TimeUnit.MILLISECONDS);
            binlogEventProducer.setBinlogFileName(EventPosition.getEventBinlogFileName(beginEvent));
            binlogEventProducer.setBinlogPosition(EventPosition.getEventBinlogNextPosition(beginEvent));
            binlogEventProducer.start();
        } catch (Exception e) {
            throw new BinlogEventProducerException("Can't stop binlogEventProducer to rewind a stream to the end of a transaction: ");
        }

        // apply begin event before data events
        applyTransactionBeginEvent();
        // apply data events
        processQueueLoop(new BinlogPositionInfo(replicantPool.getReplicantDBActiveHostServerID(),
                EventPosition.getEventBinlogFileName(commitEvent), EventPosition.getEventBinlogPosition(commitEvent)));

        if (!isRunning()) return;

        // at this point transaction must be committed by xidEvent which we rewinded to and the commit events must be applied
        if (currentTransaction != null) {
            throw new TransactionException("Transaction must be already committed at this point: " + currentTransaction);
//=======
//                break;
//
//            case FORMAT_DESCRIPTION_EVENT:
//                eventIsTracked = true;
//                break;
//
//            case STOP_EVENT:
//                eventIsTracked = true;
//                break;
//
//            default:
//                eventIsTracked = false;
//                LOGGER.warn("Unexpected event type => " + rawEventType);
//                break;
//>>>>>>> Migrating to binlog connector. Temporarily will support both parsers.
        }

        isRewinding = false;

        LOGGER.debug("Stop rewinding transaction at: " + EventPosition.getEventBinlogFileNameAndPosition(commitEvent));
    }

    public boolean isInTransaction() {
        return (currentTransaction != null);
    }

    public void commitTransaction(long timestamp, long xid) throws TransactionException {
        // manual transaction commit
        currentTransaction.setXid(xid);
        if (currentTransaction.hasBeginEvent()) currentTransaction.setBeginEventTimestamp(timestamp);
        currentTransaction.setEventsTimestamp(timestamp);
        commitTransaction();
    }

    public void commitTransaction(XidEvent xidEvent) throws TransactionException {
        currentTransaction.setFinishEvent(xidEvent);
        commitTransaction();
    }

    public void commitTransaction(QueryEvent queryEvent) throws TransactionException {
        currentTransaction.setFinishEvent(queryEvent);
        commitTransaction();
    }

    private void commitTransaction() throws TransactionException {
        // apply all the buffered events
        LOGGER.debug("/ transaction uuid: " + currentTransaction.getUuid() + ", id: " + currentTransaction.getXid());
        // apply changes from buffer and pass current metadata with xid and uuid

        try {
            if (isRewinding) {
                applyTransactionFinishEvent();
            } else {
                if (isEmptyTransaction()) {
                    LOGGER.debug("Transaction is empty");
                    dropTransaction();
                    return;
                }
                applyTransactionBeginEvent();
                applyTransactionDataEvents();
                applyTransactionFinishEvent();
            }
        } catch (EventHandlerApplyException e) {
            LOGGER.error("Failed to commit transaction: " + currentTransaction, e);
            requestShutdown();
        }
        LOGGER.debug("Transaction committed uuid: " + currentTransaction.getUuid() + ", id: " + currentTransaction.getXid());
        currentTransaction = null;
    }

    private void applyTransactionBeginEvent() throws EventHandlerApplyException, TransactionException {
        // apply begin event
        if (currentTransaction.hasBeginEvent()) {
            currentTransaction.setBeginEventTimestampToFinishEvent();
            eventDispatcher.apply(currentTransaction.getBeginEvent(), currentTransaction);
        }
    }

    private void applyTransactionDataEvents() throws EventHandlerApplyException, TransactionException {
        // apply data-changing events
        if (currentTransaction.hasFinishEvent())    currentTransaction.setEventsTimestampToFinishEvent();
        for (BinlogEventV4 event : currentTransaction.getEvents()) {
            eventDispatcher.apply(event, currentTransaction);
        }
        currentTransaction.clearEvents();
    }

    private void applyTransactionFinishEvent() throws EventHandlerApplyException, TransactionException {
        // apply commit event
        if (currentTransaction.hasFinishEvent()) {
            eventDispatcher.apply(currentTransaction.getFinishEvent(), currentTransaction);
        }
    }

    private boolean isEmptyTransaction() {
        return (currentTransaction.hasFinishEvent() && !currentTransaction.hasEvents());
    }

    private void dropTransaction() {
        LOGGER.debug("Transaction dropped");
        currentTransaction = null;
    }

    public CurrentTransaction getCurrentTransaction() {
        return currentTransaction;
    }

    private boolean isTransactionSizeLimitExceeded() {
        return configuration.getOrchestratorConfiguration().isRewindingEnabled() && (currentTransaction.getEventsCounter() > orchestratorConfiguration.getRewindingThreshold());
    }

    private void doTimestampOverride(RawBinlogEvent event) {
        if (configuration.isInitialSnapshotMode()) {
            doInitialSnapshotEventTimestampOverride(event);
        } else {
            injectFakeMicroSecondsIntoEventTimestamp(event);
        }
    }

    private void injectFakeMicroSecondsIntoEventTimestamp(RawBinlogEvent rawBinlogEvent) {

        long overriddenTimestamp = rawBinlogEvent.getTimestamp();

        if (overriddenTimestamp != 0) {
//<<<<<<< HEAD
            // timestamp is in millisecond form, but the millisecond part is actually 000 (for example 1447755881000)
            overriddenTimestamp = (overriddenTimestamp * 1000) + fakeMicrosecondCounter;
            ((BinlogEventV4HeaderImpl)(event.getHeader())).setTimestamp(overriddenTimestamp);
//=======
//            String timestampString = Long.toString(overriddenTimestamp).substring(0,10); // first ten digits
//            overriddenTimestamp = Long.parseLong(timestampString) * 1000000;
//            overriddenTimestamp += fakeMicrosecondCounter;
//            rawBinlogEvent.overrideTimestamp(overriddenTimestamp);
//>>>>>>> Migrating to binlog connector. Temporarily will support both parsers.
        }
    }

    // set initial snapshot time to unix epoch.
    private void doInitialSnapshotEventTimestampOverride(RawBinlogEvent rawBinlogEvent) {

        long overriddenTimestamp = rawBinlogEvent.getTimestamp();

        if (overriddenTimestamp != 0) {
            overriddenTimestamp = 0;
           rawBinlogEvent.overrideTimestamp(overriddenTimestamp);
        }
    }
}
