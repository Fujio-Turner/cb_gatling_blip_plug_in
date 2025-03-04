import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

/**
 * Couchbase Sync Gateway Replication Simulation
 *
 * This script simulates replication between Couchbase Lite and Sync Gateway using the BLIP protocol.
 * It supports:
 * - **Pull replication**: Downloading changes from the server to the client.
 * - **Push replication**: Uploading local client changes to the server.
 * - **Modes**: One-shot (single fetch/upload) or continuous (ongoing replication).
 * It also manages checkpoints to track replication progress and processes changes in batches.
 *
 * References (for understanding the replication and BLIP protocols):
 * - Replication Protocol: https://github.com/couchbase/couchbase-lite-core/blob/master/modules/docs/pages/replication-protocol.adoc
 * - BLIP Protocol: https://github.com/couchbase/couchbase-lite-core/blob/master/Networking/BLIP/docs/BLIP Protocol.md
 */

class CouchbaseReplicationSimulation extends Simulation {

  // **WebSocket Configuration**
  // Define the WebSocket protocol for connecting to Sync Gateway.
  // Replace "ws://your-sync-gateway-url:4984/db/_blip" with your actual Sync Gateway WebSocket endpoint.
  // - "ws://" indicates a WebSocket connection.
  // - "4984" is the default Sync Gateway WebSocket port.
  // - "db" is the database name (replace with your database).
  // - "_blip" is the endpoint for the BLIP protocol.
  val wsProtocol = ws.baseUrl("ws://your-sync-gateway-url:4984/db/_blip")

  // **Replication Mode**
  // Set the replication mode: "one-shot" (single run) or "continuous" (ongoing).
  // Change this to switch between modes based on your testing needs.
  val replicationMode = "continuous"

  // **Helper Function: Current Timestamp**
  // Returns the current time in milliseconds (Unix timestamp) as a string.
  // Used for checkpoint timestamps to track when replication progress was last saved.
  def currentTimestamp: String = System.currentTimeMillis().toString

  // **Simulation Scenario**
  // Define the sequence of actions (scenario) that each virtual user (replicator) will perform.
  val scn = scenario("Couchbase Replication Simulation")

    // **Step 1: Generate a Unique Checkpoint ID**
    // Each virtual user needs a unique identifier for its checkpoint to avoid conflicts.
    // - A checkpoint tracks replication progress (e.g., last sequence processed).
    // - Using UUID ensures no two replicators share the same checkpoint.
    .exec(session => session.set("checkpointId", java.util.UUID.randomUUID().toString))

    // **Step 2: Initialize Checkpoint Values**
    // Set initial values for replication tracking in the session (a per-user data store):
    // - "localSeq": Tracks the last sequence of local changes uploaded (starts at "0").
    // - "remoteSeq": Tracks the last sequence of server changes downloaded (starts at "0").
    // - "timestamp": Records when the checkpoint was last updated (current time).
    .exec(session => session.set("localSeq", "0").set("remoteSeq", "0").set("timestamp", currentTimestamp))

    // **Step 3: Retrieve Existing Checkpoint from Sync Gateway**
    // Send a "getCheckpoint" request via WebSocket to fetch the last saved checkpoint for this replicator.
    // - The request includes the unique "checkpointId".
    // - Sync Gateway responds with the checkpoint (if it exists) or nothing (if new).
    .exec(ws("Send getCheckpoint")
      .sendText(session => s"""{"type": "request", "method": "getCheckpoint", "properties": {"checkpointId": "${session("checkpointId").as[String]}"}}""")
      // Check the response for checkpoint data:
      // - Extracts "local-seq", "remote-seq", and "timestamp" (optional, as they may not exist yet).
      // - Saves these values to the session for later use.
      .check(ws.checkTextMessage("checkGetCheckpoint")
        .check(jsonPath("$.body.local-seq").optional.saveAs("localSeq"))
        .check(jsonPath("$.body.remote-seq").optional.saveAs("remoteSeq"))
        .check(jsonPath("$.body.timestamp").optional.saveAs("timestamp"))
      )
    )

    // **Step 4: Update Session with Checkpoint Values**
    // Process the response from "getCheckpoint":
    // - If values were retrieved, use them; otherwise, keep defaults ("0" for sequences).
    // - Ensures the session reflects the latest replication state.
    .exec(session => {
      val localSeq = session("localSeq").asOption[String].getOrElse("0")
      val remoteSeq = session("remoteSeq").asOption[String].getOrElse("0")
      val timestamp = session("timestamp").asOption[String].getOrElse(currentTimestamp)
      session.set("localSeq", localSeq).set("remoteSeq", remoteSeq).set("timestamp", timestamp)
    })

    // **Step 5: Pull Replication (Download Changes from Server)**
    // Execute pull replication based on the replication mode.

    // **One-Shot Pull Replication**
    // If mode is "one-shot", fetch changes once and stop.
    .doIfEquals(replicationMode, "one-shot") {
      // Send a "subChanges" request to get changes since the last "remoteSeq".
      exec(ws("Send subChanges")
        .sendText(session => s"""{"type": "request", "method": "subChanges", "properties": {"since": "${session("remoteSeq").as[String]}"}}""")
      )
      // Receive the response containing a list of changes and the latest sequence.
      .exec(ws("Receive changes")
        .check(ws.checkTextMessage("checkChanges")
          // Save the list of changes (array of {id, rev, deleted}) and the last sequence.
          .check(jsonPath("$.body.changes").ofType[Seq[Map[String, String]]].saveAs("changes"))
          .check(jsonPath("$.body.lastSequence").saveAs("lastSequence"))
        )
      )
      // **Step 6: Process Each Change**
      // Iterate over the list of changes to request full documents.
      .foreach("${changes}", "change") {
        exec(ws("Send requestRev")
          .sendText(session => {
            val change = session("change").as[Map[String, String]]
            // Only request documents that aren't deleted (deleted = "false" or absent).
            if (change.getOrElse("deleted", "false") == "false") {
              s"""{"type": "request", "method": "requestRev", "properties": {"id": "${change("id")}", "rev": "${change("rev")}"}}"""
            } else {
              "" // Skip deleted documents
            }
          })
        )
        // Receive the full document and save it (e.g., for validation or logging).
        .exec(ws("Receive rev")
          .check(ws.checkTextMessage("checkRev")
            .check(bodyString.saveAs("document"))
          )
        )
      }
      // **Step 7: Update Checkpoint**
      // Save the new replication progress (lastSequence) to the checkpoint.
      .exec(ws("Send setCheckpoint")
        .sendText(session => s"""{"type": "request", "method": "setCheckpoint", "properties": {"checkpointId": "${session("checkpointId").as[String]}", "local-seq": "${session("localSeq").as[String]}", "remote-seq": "${session("lastSequence").as[String]}", "timestamp": "$currentTimestamp"}}""")
      )
      // **Step 8: Close Connection**
      // In one-shot mode, close the WebSocket after completing the pull.
      .exec(ws("Close WebSocket").close)
    }

    // **Continuous Pull Replication**
    // If mode is "continuous", keep fetching changes for a set duration (e.g., 60 seconds).
    .doIfEquals(replicationMode, "continuous") {
      during(60.seconds) {
        // Send "subChanges" with "continuous: true" to keep the feed open for new changes.
        exec(ws("Send subChanges")
          .sendText(session => s"""{"type": "request", "method": "subChanges", "properties": {"since": "${session("remoteSeq").as[String]}", "continuous": true}}""")
        )
        // Receive changes as they arrive; timeout after 10 seconds if no changes.
        .exec(ws("Receive changes")
          .check(ws.checkTextMessage("checkChanges").timeout(10.seconds)
            .check(jsonPath("$.body.changes").ofType[Seq[Map[String, String]]].saveAs("changes"))
            .check(jsonPath("$.body.lastSequence").saveAs("lastSequence"))
          )
        )
        // Process each change (same as one-shot mode).
        .foreach("${changes}", "change") {
          exec(ws("Send requestRev")
            .sendText(session => {
              val change = session("change").as[Map[String, String]]
              if (change.getOrElse("deleted", "false") == "false") {
                s"""{"type": "request", "method": "requestRev", "properties": {"id": "${change("id")}", "rev": "${change("rev")}"}}"""
              } else {
                ""
              }
            })
          )
          .exec(ws("Receive rev")
            .check(ws.checkTextMessage("checkRev")
              .check(bodyString.saveAs("document"))
            )
          )
        }
        // Update the checkpoint after processing each batch of changes.
        .exec(ws("Send setCheckpoint")
          .sendText(session => s"""{"type": "request", "method": "setCheckpoint", "properties": {"checkpointId": "${session("checkpointId").as[String]}", "local-seq": "${session("localSeq").as[String]}", "remote-seq": "${session("lastSequence").as[String]}", "timestamp": "$currentTimestamp"}}""")
        )
        // Update the session’s "remoteSeq" to the latest sequence for the next iteration.
        .exec(session => session.set("remoteSeq", session("lastSequence").as[String]))
        // Pause for 100ms to simulate the checkpoint update interval (avoid overwhelming the server).
        .pause(100.milliseconds)
      }
    }

    // **Step 9: Push Replication (Upload Local Changes to Server)**
    // Simulate pushing local changes to Sync Gateway (shown for continuous mode).

    .doIfEquals(replicationMode, "continuous") {
      during(60.seconds) {
        // **Step 10: Simulate Local Changes**
        // Generate 100 mock local documents to upload.
        .exec(session => {
          // Create a sequence of 100 changes (e.g., doc_1, doc_2, ...).
          val changes = (1 to 100).map(i => Map("id" -> s"doc_$i", "rev" -> s"1-$i")).toSeq
          // Increment "localSeq" by 100 to reflect these new changes.
          val newLocalSeq = (session("localSeq").as[String].toInt + 100).toString
          session.set("localChanges", changes).set("newLocalSeq", newLocalSeq)
        })
        // **Step 11: Propose Changes**
        // Send "proposeChanges" to ask Sync Gateway which documents it needs.
        .exec(ws("Send proposeChanges")
          .sendText(session => {
            val changes = session("localChanges").as[Seq[Map[String, String]]]
            // Format changes as a JSON array of [id, rev] pairs.
            val changesJson = changes.map(c => s"""["${c("id")}", "${c("rev")}"]""").mkString("[", ",", "]")
            s"""{"type": "request", "method": "proposeChanges", "properties": {"changes": $changesJson}}"""
          })
          // Response lists indices of changes the server needs.
          .check(ws.checkTextMessage("checkProposeChanges")
            .check(jsonPath("$.body.needed").ofType[Seq[Int]].saveAs("neededIndices"))
          )
        )
        // **Step 12: Send Needed Documents**
        // Send full documents for the changes the server requested.
        .foreach("${neededIndices}", "index") {
          exec(ws("Send full document")
            .sendText(session => {
              val changes = session("localChanges").as[Seq[Map[String, String]]]
              val idx = session("index").as[Int]
              val change = changes(idx)
              // Send a simple document with a "key": "value" body (modify as needed).
              s"""{"type": "request", "method": "sendRev", "properties": {"id": "${change("id")}", "rev": "${change("rev")}", "body": {"key": "value"}}}"""
            })
          )
        }
        // **Step 13: Update Checkpoint**
        // Save the new "localSeq" after uploading changes.
        .exec(ws("Send setCheckpoint")
          .sendText(session => s"""{"type": "request", "method": "setCheckpoint", "properties": {"checkpointId": "${session("checkpointId").as[String]}", "local-seq": "${session("newLocalSeq").as[String]}", "remote-seq": "${session("remoteSeq").as[String]}", "timestamp": "$currentTimestamp"}}""")
        )
        // Update the session’s "localSeq" for the next iteration.
        .exec(session => session.set("localSeq", session("newLocalSeq").as[String]))
        // Pause for 100ms to simulate the checkpoint update interval.
        .pause(100.milliseconds)
      }
    }

  // **Simulation Setup**
  // Configure how the simulation runs:
  // - Ramp up 10 virtual users over 10 seconds to simulate gradual load.
  setUp(
    scn.inject(rampUsers(10).during(10.seconds))
  ).protocols(wsProtocol)
}
