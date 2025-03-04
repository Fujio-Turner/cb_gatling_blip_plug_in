# Couchbase Sync Gateway(App Services) Replication Load Testing with Gatling

**Status: Work In Progress / Draft**

This repository contains a Gatling script designed to load test the replication functionality of **Couchbase Sync Gateway**. The script simulates multiple clients interacting with Sync Gateway, mimicking the behavior of **Couchbase Lite** clients. It supports both **one-shot** (single fetch/upload) and **continuous** (ongoing) replication modes, making it a flexible tool for testing Sync Gateway’s performance under load.

**Important Note**: This code is a draft and still under active development. It’s a starting point for testing and will evolve with refinements and additional features over time.

## What the Script Does
The script replicates the process that Couchbase Lite clients use to synchronize data with Sync Gateway. Here’s a breakdown of its key actions:

- **Unique Checkpoint Creation**: Each simulated client generates a unique checkpoint ID (using a UUID) to track its replication progress independently.
- **Pull Replication**: 
  - In **one-shot mode**, it fetches changes since the last checkpoint, processes them (e.g., up to 100 documents), updates the checkpoint, and closes the connection.
  - In **continuous mode**, it keeps the connection open, fetching and processing changes as they occur, with periodic checkpoint updates.
- **Push Replication**: Simulates local changes (e.g., 100 documents), proposes them to Sync Gateway, uploads accepted changes, and updates the checkpoint.
- **Realistic Behavior**: Uses Gatling’s WebSocket support to communicate via the **BLIP protocol**, processes changes in batches, and mimics periodic checkpoint updates (e.g., every 100ms).

This allows you to stress-test Sync Gateway’s replication capabilities and evaluate its scalability and performance.

## Prerequisites
Before using this script, ensure you have:
- **Gatling**: Download it from [Gatling.io](https://gatling.io/download/).
- **Couchbase Sync Gateway**: A running instance with a configured database.
- **Java**: Version 8 or higher (required by Gatling).
- **Scala**: The script is written in Scala, Gatling’s scripting language.

## Setup Instructions
1. **Clone the Repository**:
   ```bash
   git clone https://github.com/your-username/your-repo-name.git
   cd your-repo-name
