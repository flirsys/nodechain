([перейти в Рус. версию](README.md))

# 🦊 NodeChain

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 17" />
  <img src="https://img.shields.io/badge/Spring_Boot-3.3.5-6DB33F?style=for-the-badge&logo=springboot&logoColor=white" alt="Spring Boot" />
  <img src="https://img.shields.io/badge/SQLite-WAL_Mode-003B57?style=for-the-badge&logo=sqlite&logoColor=white" alt="SQLite" />
  <img src="https://img.shields.io/badge/Consensus-Proof--of--Work-F7931A?style=for-the-badge&logo=bitcoin&logoColor=white" alt="PoW" />
  <img src="https://img.shields.io/badge/Crypto-Ed25519_%26_RFC_6962-blue?style=for-the-badge" alt="Ed25519" />
  <img src="https://img.shields.io/badge/P2P-Binary_GZIP_Protocol-blueviolet?style=for-the-badge" alt="Binary P2P" />
  <img src="https://img.shields.io/badge/License-MIT-green?style=for-the-badge" alt="License" />
</p>

> **Full-featured distributed blockchain node built on Java 17 and Spring Boot.**
> Implemented without heavyweight external blockchain frameworks: pure JDK cryptography (`Ed25519`, `SHA-256`, `RFC 6962 Merkle Tree`), lightweight SQLite in WAL mode, custom binary P2P protocol with compression and overflow protection, standalone SPA client `tiny_pc_client` on Mithril.js, and a strict REST API for client and wallet integrations.

---

## 📑 Table of Contents

- [Why and Who This Project Is For](#-why-and-who-this-project-is-for)
- [System Architecture](#-system-architecture)
- [Cryptography and Fox Addressing](#-cryptography-and-fox-addressing)
- [Account-Based Model](#-account-based-model)
- [Consensus: Proof-of-Work and Merkle Tree](#-consensus-proof-of-work-and-merkle-tree)
- [Chain Reorganization (Fork Choice & Reorg)](#-chain-reorganization-fork-choice--reorg)
- [Binary P2P Network Protocol](#-binary-p2p-network-protocol)
- [Data Layer: SQLite + HikariCP](#-data-layer-sqlite--hikaricp)
- [REST API v1 (Full Specification)](#-rest-api-v1-full-specification)
- [Desktop Web Client (tiny_pc_client)](#-desktop-web-client-tiny_pc_client)
- [Configuration and Environment Variables](#-configuration-and-environment-variables)
- [Quick Start](#-quick-start)
  - [Building from Source](#building-from-source)
  - [Running a Single Node](#running-a-single-node)
  - [Running a Local Network (Multi-Node Testnet)](#running-a-local-network-multi-node-testnet)
- [Key Generation and Transaction Submission (CLI & Python)](#-key-generation-and-transaction-submission-cli--python)
- [Security Mechanisms](#-security-mechanisms)
- [Testing](#-testing)
- [Project Structure](#-project-structure)

---

## 💡 Why and Who This Project Is For

Most educational or lightweight blockchains suffer from one of two flaws:
1. Either a toy 200-line in-memory script lacking persistence, P2P networking, and signature validation.
2. Or monstrous monoliths (Hyperledger, Geth, Substrate), where transaction mathematics, consensus rules, and packet wire protocols are obscured behind excessive abstractions.

**NodeChain** is an honest, transparent, and engineered implementation of a decentralized node. No "magic" involved:
* 🏎️ **Zero Heavyweight Dependencies**: standard Java runtime cryptography (`java.security` Ed25519 and SHA-256).
* 📦 **Efficient Storage**: embedded SQLite database configured in high-performance WAL (`Write-Ahead Logging`) mode backed by HikariCP connection pooling.
* 🌐 **Real Binary P2P**: custom framing over raw TCP sockets featuring magic bytes, opcodes, mutual signed handshakes with session nonces, and automatic GZIP compression for large block batches.
* 🛡️ **Exploit Resilience**: built-in protection against Transaction Malleability, mempool DoS eviction, GZIP bombs, timestamp replay attacks, RFC 6962 second-preimage attacks, and invalid nonces.

---

## 🏛 System Architecture

The node consists of cleanly separated subsystems designed around **Static-First & Clean Layers** principles:

```mermaid
flowchart TB
    subgraph Clients["External World"]
        Web["tiny_pc_client (Web / SPA)"]
        CLI[cURL / CLI Tools]
        PeerNode[Remote P2P Nodes]
    end

    subgraph NodeChain["NodeChain Core (Spring Boot)"]
        direction TB

        subgraph Ingress["Network Ingress"]
            REST["ApiController (HTTP / JSON)\nPort: 8080"]
            P2P["P2pClientManager (TCP Binary Socket)\nPort: 9090"]
        end

        subgraph Engine["Core Engine"]
            Wallet["NodeWalletService\n(nodewallet.dat: Ed25519)"]
            Mempool["Mempool Manager\n(In-Memory + ConcurrentHashMap)"]
            Miner["Auto-Miner Service\n(Scheduled PoW Engine)"]
            Blockchain["BlockchainService\n(State Machine & Fork Resolution)"]
            Consensus["ConsensusUtil & CryptoUtil\n(Merkle Root, PoW, Ed25519)"]
        end

        subgraph Storage["Persistence Layer"]
            RepoBlock["BlockRepository"]
            RepoTx["TransactionRepository"]
            RepoAcc["AccountRepository"]
            RepoPeer["PeerRepository"]
            SQLite[("SQLite Embedded Database\n(PRAGMA WAL Mode)")]
        end
    end

    Web -->|HTTP REST| REST
    CLI -->|HTTP REST| REST
    PeerNode <-->|Binary Frames / TCP| P2P

    REST --> Blockchain
    P2P <--> Blockchain
    P2P <--> Wallet

    Blockchain --> Mempool
    Blockchain --> Miner
    Blockchain --> Consensus
    Miner --> Wallet

    Blockchain --> RepoBlock
    Blockchain --> RepoTx
    Blockchain --> RepoAcc
    P2P --> RepoPeer

    RepoBlock --> SQLite
    RepoTx --> SQLite
    RepoAcc --> SQLite
    RepoPeer --> SQLite
```

---

## 🔐 Cryptography and Fox Addressing

### 1. Key Algorithm and Signatures
* **Asymmetric Algorithm**: `Ed25519` (RFC 8032) powered by the native `java.security` provider in Java 17+.
* **Public Key Format**: X.509 SPKI ASN.1, encoded in `Base64` (raw DER key size: strictly 44 bytes, Base64: 60 characters instead of 392 for RSA).
* **Private Key Format**: PKCS#8 ASN.1, encoded in `Base64` (raw DER key size: strictly 48 bytes, Base64: 64 characters).
* **Digital Signature Algorithm**: `Ed25519` (raw binary signature: strictly 64 bytes, Base64: 88 characters instead of 344 for RSA).
* **Performance**: Signature verification and generation is 10–20x faster, while network transmission overhead for keys and signatures is reduced by more than 4x.

### 2. Fox Address Format and CRC32 Checksum
Wallet addresses in NodeChain are **64-character strings** (`[0-9a-fox]{64}`), deterministically derived from the public key with brand symbols `'f'`, `'o'`, and `'x'` substituted in the prefix and protected by a 32-bit checksum:

1. Calculate `SHA-256` hash of DER-encoded public key bytes (`PublicKey.getEncoded()`).
2. Convert the digest into a hex string.
3. The first 3 raw bytes of the digest dictate brand character positions:
   * Byte 0 selects the position for `'f'` (within index range `0..4`).
   * Byte 1 selects the position for `'o'` (range `0..4`, excluding `'f'`).
   * Byte 2 selects the position for `'x'` (range `0..4`, excluding `'f'` and `'o'`).
4. The first 56 characters form the address payload.
5. The final 8 hex characters (indices 56..63) contain the `CRC32` checksum (IEEE 802.3) of the first 56 characters in lowercase. This guarantees protection against typos and accidental transfers to non-existent addresses — tampering with even a single character causes `isValidAddress()` to fail.

> **Valid Address Example:**  
> `6foxabbe6c0d624d0bb3de4c8375643f4fce0c9744c0096f14b919dfe3f031f1`

### 3. Canonical Signing Data Format (Anti-Malleability & Cross-Chain Replay)
Transactions are strictly signed over a deterministic canonical string delimited by `|` with mandatory `chainId`:
- If comment is empty or contains only whitespace:  
  `chainId|from|to|amount|fee|nonce`
- If comment is present:  
  `chainId|from|to|amount|fee|nonce|comment.trim()`

The transaction hash is computed from a deterministic string (empty comment slot is preserved):  
- If comment is empty:  
  `chainId|from|to|amount|fee|nonce||signature`
- If comment is present:  
  `chainId|from|to|amount|fee|nonce|comment.trim()|signature`

All addresses are normalized to **lowercase** prior to hashing and verification, and comments are trimmed, preventing signature or hash divergence between nodes and clients.

---

## 👤 Account-Based Model

Unlike UTXO (Bitcoin model), NodeChain operates on an **Account-Based state model** (similar to Ethereum):

| Parameter | Description |
| :--- | :--- |
| **`address`** | Unique 64-character fox-address of the account. |
| **`balance`** | Confirmed balance in atomic units (`long`). 1 coin = $10^8$ atomic units. |
| **`nonce`** | Strictly monotonically increasing sequence counter of sent transactions (starts at `0`). |

### Nonce Handling Rules and Mempool Pipelining
* **Replay Protection**: a transaction is valid if and only if its `nonce` matches the sender's expected nonce.
* **Mempool Pipelining**: a sender can broadcast multiple sequential transactions into a single block:
  - `Tx_0` → nonce 0
  - `Tx_1` → nonce 1
  - `Tx_2` → nonce 2

  The node automatically chains them in memory and commits them sequentially.
* **Unconfirmed Balance**: incoming unconfirmed funds in the mempool **cannot be spent** before inclusion in a block. Attempts to spend unconfirmed incoming balances are rejected with `409 Conflict`.

---

## ⛏ Consensus: Proof-of-Work and Merkle Tree

### 1. Block Structure
```text
+--------------------------------------------------------------------------+
| Block Header                                                             |
|   height:        42                                                      |
|   chainId:       "foxchain-main-1"                                       |
|   previousHash:  00a8f93b...                                             |
|   merkleRoot:    7d2c418e...                                             |
|   minerAddress:  6foxabbe6c0d624d0bb3de4c8375643f4fce0c9744c0096f14b9...  |
|   timestamp:     1730912400000                                           |
|   difficulty:    4                                                       |
|   nonce:         184729                                                  |
|   hash:          0004bca1... (starts with "0" * difficulty)              |
+--------------------------------------------------------------------------+
| Transactions [ tx0, tx1, tx2, ... ]                                      |
+--------------------------------------------------------------------------+
```

### 2. Proof-of-Work (PoW) and Difficulty Adjustment (Bitcoin-style DAA & MTP-11)
The miner increments the block `nonce` until the block hash satisfies difficulty criteria:
- **Block Payload** = `height|chainId|previousHash|merkleRoot|minerAddress|timestamp|difficulty|nonce`
- **Block Hash** = `SHA256(Block Payload)`
- **Valid PoW** = `Block Hash` begins with `difficulty` leading hex zeros (`00...0`)
- **Median-Time-Past (MTP-11)**: the block timestamp must be strictly greater than the median timestamp of the previous 11 blocks (`next.timestamp > mtp`), preventing Time Warp difficulty manipulation.
- **Timestamp Drift Limit**: block timestamp cannot exceed local node time by more than **30 seconds** (`next.timestamp <= now + 30_000ms`).
- **Difficulty Adjustment Algorithm (DAA)**: every `blockchain.difficulty-adjustment-interval` blocks (default: 10), difficulty retargets based on elapsed time between the latest block and the anchor block:
  - If actual time span $< \text{targetTime} / 2$, difficulty increases by $+1$ (up to `max-difficulty`).
  - If actual time span $> \text{targetTime} \times 2$, difficulty decreases by $-1$ (down to `min-difficulty`).
  - Blocks with spoofed or mismatched difficulty are rejected by peers (`b.difficulty != expectedDiff`).
- **Genesis Block**: created deterministically with network binding via `Block.createGenesis(chainId, minerAddress, difficulty, timestamp)`.
- **Minimum Relay Fee**: transactions require a minimum fee of `MIN_RELAY_FEE = 1L` atomic unit to protect mempool from spam.

### 3. Merkle Tree (RFC 6962 Domain Separation)
To protect against Second Preimage Attacks—where an internal Merkle tree node is submitted as a leaf transaction—the hashing implements strict domain separation in compliance with RFC 6962:
* **Transaction Leaves (`Leaf`)**: hashed with prefix `00`:  
  `H_leaf = SHA256("00" + tx.hash)`
* **Internal Nodes (`Internal`)**: concatenated and hashed with prefix `01`:  
  `H_parent = SHA256("01" + H_left + H_right)`
* If the number of nodes at any level is odd, the last element is duplicated.
* For empty blocks without transactions, `merkleRoot = "0"`.

```mermaid
graph TD
    Root["Merkle Root: H('01' + H01 + H23)"]
    H01["H01: H('01' + H0 + H1)"]
    H23["H23: H('01' + H2 + H3)"]
    H0["H0: H('00' + Tx0 Hash)"]
    H1["H1: H('00' + Tx1 Hash)"]
    H2["H2: H('00' + Tx2 Hash)"]
    H3["H3: H('00' + Tx3 Hash or duplicate)"]

    Root --> H01
    Root --> H23
    H01 --> H0
    H01 --> H1
    H23 --> H2
    H23 --> H3
```

### 4. Bitcoin Core Subsidy Halving Algorithm
Emission of new coins follows the canonical Bitcoin Core halving formula:

- `halvings = height / halvingInterval`
- For `halvings >= 64`, subsidy equals strictly `0`
- Otherwise: `Subsidy = miningReward >> halvings`

Every `blockchain.halving-interval` blocks (default: 210,000), the base block reward is halved via bitwise right shift, enforcing a mathematical cap on total supply.

---

## 🔀 Chain Reorganization (Fork Choice & Reorg)

When a remote peer advertises a competing chain, the node resolves forks via the **Highest Cumulative Chain Work** rule:

**ChainWork** = sum of `16^difficulty` across all blocks in the branch

```mermaid
sequenceDiagram
    autonumber
    participant P as Remote Peer
    participant N as Local Node
    participant DB as SQLite DB

    P->>N: BLOCKS_DATA (Alternative block branch)
    Note over N: Search for Common Ancestor
    Note over N: Compute Cumulative ChainWork
    alt Branch ChainWork <= Local ChainWork
        N-->>P: Fork rejected (local chain is stronger)
    else Branch ChainWork > Local ChainWork
        Note over N: In-Memory Dry-Run Simulation:
        Note over N: 1. Roll back local blocks (revert txs to 'pending')
        Note over N: 2. Apply incoming branch blocks sequentially
        alt Validation failure in any block or balance
            Note over N: Rollback simulation, chain unchanged
        else Simulation successful
            N->>DB: Atomic Transaction:
            Note over DB: - Revert displaced txs to 'pending'<br/>- Delete orphaned blocks<br/>- Update account balances<br/>- Commit new blocks and 'confirmed' statuses
            Note over N: Restore displaced transactions to Mempool
            Note over N: Evict stale nonces from Mempool
        end
    end
```

### Step-Back Fork Locator and Loop Protection
If an incoming block batch does not connect to the tip of the local chain:
1. **Reorganization Depth Limit (`MAX_REORG_DEPTH = 20`)**: the node never rolls back more than 20 blocks from the current tip, safeguarding the network from deep reorgs, forks, and double-spending attacks.
2. **Step-Back**: the node automatically steps back along the chain in increments of 20 blocks (`Math.max(1, lowestReceived - 20)`) and sends a `GET_BLOCKS` request.
3. **Step-Back Loop Guard**: the `peerStepBackCount` counter monitors consecutive step-back requests per connection. If a peer fails to connect branches and triggers more than 10 consecutive step-backs (`stepBacks > 10`), the TCP connection is immediately terminated, neutralizing DoS loops.
4. Upon locating a locally known Common Ancestor and verifying superior cumulative chain work (`branchWork > curWork`), the node executes dry-run simulation and an atomic `reorganizeChain`.

---

## 📡 Binary P2P Network Protocol

To eliminate overhead and maximize sync throughput, nodes communicate over a custom binary framing protocol via raw TCP sockets.

### 1. Wire Frame Layout

```text
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|          MAGIC (0x4658 "FX")          | FLAG  |               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                        REQUEST_ID                             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  OP_CODE  |                   PAYLOAD LENGTH                  |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+                                                               +
|                      PAYLOAD BYTES                            |
+                     (JSON / GZIP)                             +
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

| Field | Offset | Type | Size | Description |
| :--- | :---: | :--- | :--- | :--- |
| **`MAGIC`** | `0..1` | `short` | 2 bytes | Constant `0x4658` (ASCII `"FX"`). Packets with mismatching magic are immediately dropped. |
| **`FLAG`** | `2` | `byte` | 1 byte | `0x00` = RAW JSON, `0x01` = GZIP-compressed JSON. |
| **`REQUEST_ID`** | `3..6` | `int32` | 4 bytes | Request identifier for matching asynchronous responses. |
| **`OP_CODE`** | `7` | `byte` | 1 byte | Command operation code (see table below). |
| **`LENGTH`** | `8..11` | `int32` | 4 bytes | Length of the payload body in bytes (max **4 MiB**; up to 64 KiB for control packets, up to 16 bytes for PING/PONG). |
| **`PAYLOAD`** | `12+` | `byte[]` | `LENGTH` | Message payload in JSON format (compressed with GZIP if FLAG=1). |

### 2. Operation Codes Table (OpCodes)

| OpCode | Hex | Name | Purpose |
| :---: | :---: | :--- | :--- |
| **`1`** | `0x01` | `HANDSHAKE` | Initial greeting carrying cryptographic node signature and session nonce. |
| **`2`** | `0x02` | `HANDSHAKE_ACK` | Mutual handshake acknowledgment with local chain state. |
| **`3`** | `0x03` | `PING` | Peer keep-alive check (dispatched every 20 seconds). |
| **`4`** | `0x04` | `PONG` | Heartbeat response (updates peer `last_seen` timestamp). |
| **`16`** | `0x10` | `GET_PEERS` | Request for list of active known network peers. |
| **`17`** | `0x11` | `PEERS_LIST` | Transmit known peer addresses (Gossip network discovery). |
| **`32`** | `0x20` | `GET_BLOCKS` | Request a batch of blocks from specified height (up to 100). |
| **`33`** | `0x21` | `BLOCKS_DATA` | Transmit array of block objects in response to `GET_BLOCKS`. |
| **`34`** | `0x22` | `NEW_BLOCK_ANNOUNCE` | Gossip broadcast of newly mined block across the network. |
| **`48`** | `0x30` | `NEW_TX_ANNOUNCE` | Gossip broadcast of new pending transaction to mempools. |

### 3. Cryptographic Handshake (Mutual Authentication)
Incoming TCP connections are sandboxed: until the remote node delivers a valid `HANDSHAKE`, any other packet triggers immediate socket termination.

**Handshake Message Payload:**
```json
{
  "nodeId": "f5ox17c91e0a29b2b2b1cf8813bc30e386abed50e82845bf13c8f8b8a531639d",
  "chainId": "foxchain-main-1",
  "height": 105,
  "latestBlockHash": "004aef72c81a...",
  "p2pPort": 9090,
  "version": "1.0.0",
  "timestamp": 1730912450123,
  "publicKey": "MCowBQYDK2VwAyEA4Q8w8i...",
  "signature": "81c03fa99b1a7d...",
  "sessionNonce": "b6a8e847-f495-46f9-b4b1-8406734d8ef0"
}
```

* `nodeId` must strictly match the fox address calculated from `publicKey` (Ed25519).
* Signed data: `nodeId|chainId|height|latestBlockHash|p2pPort|timestamp|sessionNonce`.
* **Strict Nonce Anti-Replay Guard**: the `sessionNonce` field is strictly required. All seen nonces are registered in `seenHandshakeNonces`. Replayed handshake frames are rejected immediately. Stale nonces are automatically pruned when the cache exceeds 2,000 entries.
* Timestamp acceptance window: -60s to +120s from node's local clock.
* **Bidirectional Gossip**: upon accepting a connection, the node records the peer's listening `p2pPort` in the `peers` table and immediately triggers peer exchange via `GET_PEERS` / `PEERS_LIST`.
* **Anti-Eclipse Connection Quotas**: connections per external IP are capped at 2; total connections are bounded at 64 with `MAX_INBOUND_CONNECTIONS = 48`, guaranteeing at least 16 slots reserved exclusively for honest outbound connections.
* **SSRF & Peer Poisoning Network Guard**: `isSafePeerAddress` rejects connections to system ports (< 1024), sensitive application ports (22, 23, 25, 80, 443, 3306, 5432, 6379, 27017, 11211, 8080), RFC 1918 private subnets (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), broadcast IPs, and cloud metadata endpoints (`169.254.0.0/16`) for external peers while permitting isolated local testing on localhost.
* **Slowloris DoS Protection**: socket timeout is set to 10s, and frame reads in `NodePacket.readFullyWithTimeout` enforce a strict 10s completion deadline.
* **Genesis Adoption on Fast-Sync (`adoptPeerGenesisBlock` / `adoptPeerGenesisChain`)**: a fresh node at height 0 only adopts a peer genesis if `blockchain.genesis-address` is explicitly configured locally and matches the remote genesis miner address. Foreign unverified genesis blocks are rejected immediately.

---

## 🗄 Data Layer: SQLite + HikariCP

The database runs on native `sqlite-jdbc` with fine-tuned parameters for blockchain workloads:

* **`PRAGMA journal_mode = WAL;`** — Write-Ahead Logging permits concurrent reads without blocking write transactions.
* **`PRAGMA synchronous = NORMAL;`** — optimal balance between durability and block commit latency.
* **`PRAGMA busy_timeout = 10000;`** — file lock acquisition timeout of up to 10 seconds to eliminate `SQLITE_BUSY`.
* **`maximumPoolSize = 10` & `connectionInitSql`** — HikariCP connection pool of 10 connections enforces PRAGMAs (`WAL`, `synchronous = NORMAL`, `busy_timeout = 10000`) on **every** pooled connection via `connectionInitSql`, eliminating `SQLITE_BUSY` errors during concurrent REST reads and background P2P execution.

### Database Schema

```sql
-- Blockchain blocks table
CREATE TABLE IF NOT EXISTS blocks (
  height INTEGER PRIMARY KEY,
  chain_id TEXT NOT NULL,
  hash TEXT UNIQUE NOT NULL,
  previous_hash TEXT NOT NULL,
  merkle_root TEXT NOT NULL,
  miner_address TEXT NOT NULL,
  tx_count INTEGER NOT NULL,
  timestamp INTEGER NOT NULL,
  difficulty INTEGER NOT NULL,
  nonce INTEGER NOT NULL
);

-- Transactions table
CREATE TABLE IF NOT EXISTS transactions (
  hash TEXT PRIMARY KEY,
  block_height INTEGER,
  block_hash TEXT,
  chain_id TEXT NOT NULL,
  tx_from TEXT NOT NULL,
  tx_to TEXT NOT NULL,
  amount INTEGER NOT NULL,
  fee INTEGER NOT NULL,
  nonce INTEGER NOT NULL,
  public_key TEXT NOT NULL,
  signature TEXT NOT NULL,
  comment TEXT,
  timestamp INTEGER NOT NULL,
  status TEXT NOT NULL,          -- 'pending' | 'confirmed' | 'failed'
  tx_index INTEGER DEFAULT 0     -- In-block position for Merkle Root validation
);

-- Indexes for pagination and history lookups
CREATE INDEX IF NOT EXISTS idx_tx_from ON transactions(tx_from);
CREATE INDEX IF NOT EXISTS idx_tx_to ON transactions(tx_to);
CREATE INDEX IF NOT EXISTS idx_tx_block_height ON transactions(block_height);
CREATE INDEX IF NOT EXISTS idx_tx_status ON transactions(status);

-- Account state ledger
CREATE TABLE IF NOT EXISTS accounts (
  address TEXT PRIMARY KEY,
  balance INTEGER NOT NULL,
  nonce INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

-- Discovered network peers
CREATE TABLE IF NOT EXISTS peers (
  host TEXT NOT NULL,
  port INTEGER NOT NULL,
  last_seen INTEGER NOT NULL,
  PRIMARY KEY (host, port)
);
```

---

## 🌐 REST API v1 (Full Specification)

API listens by default on port `8080` with CORS support for local web wallets and block explorers (`originPatterns = { "http://localhost:*", "http://127.0.0.1:*", "null" }`).

### 1. Address State
Returns confirmed balance, unconfirmed balance (accounting for pending outgoing transactions in mempool), current `nonce`, and total transaction count.

* **URL**: `GET /api/v1/addresses/{address}`
* **Request Example**:
  ```bash
  curl http://localhost:8080/api/v1/addresses/f5ox17c91e0a29b2b2b1cf8813bc30e386abed50e82845bf13c8f8b8a531639d
  ```
* **Success Response (200 OK)**:
  ```json
  {
    "address": "f5ox17c91e0a29b2b2b1cf8813bc30e386abed50e82845bf13c8f8b8a531639d",
    "balance": 100000000,
    "unconfirmedBalance": 99950000,
    "nonce": 1,
    "txCount": 1
  }
  ```
* **Errors**:
  * `400 Bad Request`: `{"code": 400, "message": "Некорректный формат адреса"}`
  * `404 Not Found`: `{"code": 404, "message": "Адрес еще не совершал транзакций и не имеет баланса"}`

---

### 2. Recommended Network Fee
Estimates required fee based on requested transaction priority.

* **URL**: `GET /api/v1/transactions/fee?priority={low|normal|high}` *(default: normal)*
* **Request Example**:
  ```bash
  curl http://localhost:8080/api/v1/transactions/fee?priority=high
  ```
* **Success Response (200 OK)**:
  ```json
  {
    "fee": 100,
    "priority": "high"
  }
  ```
  *(Values: `low` = 25, `normal` = 50, `high` = 100 atomic units)*

---

### 3. Mempool Contents
Inspects unconfirmed transactions currently queued for mining.

* **URL**: `GET /api/v1/transactions/mempool`
* **Request Example**:
  ```bash
  curl http://localhost:8080/api/v1/transactions/mempool
  ```
* **Success Response (200 OK)**:
  ```json
  {
    "count": 1,
    "transactions": [
      {
        "hash": "a4d3f2...",
        "from": "f5ox...",
        "to": "8fox...",
        "amount": 50000,
        "fee": 50,
        "nonce": 0,
        "publicKey": "MCowBQYDK2VwAyEA...",
        "signature": "sig...",
        "comment": "Coffee payment",
        "timestamp": 1730912460000,
        "status": "pending"
      }
    ]
  }
  ```

---

### 4. Address Transaction History (with Pagination)
Returns list of incoming and outgoing transactions for an address, sorted by timestamp descending.

* **URL**: `GET /api/v1/addresses/{address}/transactions?limit={limit}&offset={offset}`
* **Query Parameters**:
  * `limit` (int, default: 25, clamped range: 1..100)
  * `offset` (int, default: 0, clamped range: 0..10000)
* **Request Example**:
  ```bash
  curl "http://localhost:8080/api/v1/addresses/f5ox.../transactions?limit=10&offset=0"
  ```
* **Success Response (200 OK)**:
  ```json
  {
    "total": 1,
    "txs": [
      {
        "hash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "from": "f5ox...",
        "to": "8fox...",
        "amount": 25000,
        "fee": 50,
        "comment": "Order #42 payment",
        "timestamp": 1730912000000,
        "status": "confirmed",
        "blockHeight": 12,
        "confirmations": 5
      }
    ]
  }
  ```

---

### 5. Send Signed Transaction
Validates cryptographic signature, sender balance, and sequential nonce, inserts transaction into local mempool, and broadcasts it to P2P peers.

* **URL**: `POST /api/v1/transactions/send`
* **Headers**: `Content-Type: application/json`
* **Request Body Parameters**:
  * `chainId` (*string*, required) — blockchain network identifier (e.g. `"foxchain-main-1"`).
  * `from` (*string*, required) — 64-character sender fox address.
  * `to` (*string*, required) — 64-character recipient fox address (must not equal `from`).
  * `amount` (*long*, required) — transfer amount in atomic units ($> 0$).
  * `fee` (*long*, optional, default: `50`) — network fee in atomic units ($\ge 0$).
  * `nonce` (*long*, required) — sequential transaction counter ($\ge 0$).
  * `publicKey` (*string*, required) — sender's public key in Base64 (X.509 SPKI Ed25519, 60 characters).
  * `signature` (*string*, required) — digital signature in Base64 (`Ed25519`, 88 characters).
  * `comment` (*string*, optional, default: `""`, max 256 characters) — optional note.
* **Request Body**:
  ```json
  {
    "chainId": "foxchain-main-1",
    "from": "f5ox17c91e0a29b2b2b1cf8813bc30e386abed50e82845bf13c8f8b8a531639d",
    "to": "8fox32a11b0e29b2b2b1cf8813bc30e386abed50e82845bf13c8f8b8a531639a",
    "amount": 10000,
    "fee": 50,
    "nonce": 0,
    "publicKey": "MCowBQYDK2VwAyEA4Q8w8i02w9Gk2h6H2M7lFk4mP0...",
    "signature": "81c03fa99b1a7d6e5c4b3a2f1e0d9c8b7a6f5e4d3c2b1a0f...",
    "comment": "Transfer to friend"
  }
  ```
* **Success Response (200 OK)**:
  ```json
  {
    "hash": "c83b8a1e2d4...",
    "status": "pending",
    "addedAt": 1730912500123,
    "nonce": 0,
    "fee": 50
  }
  ```
* **Standard Error Responses**:
  * `400 Bad Request`: `{"code": 400, "message": "Подпись не верна"}` or `{"code": 400, "message": "Некорректный формат адреса"}`
  * `400 Bad Request`: `{"code": 400, "message": "Некорректный формат данных в теле запроса"}` (invalid JSON, type mismatch, or float value)
  * `409 Conflict`: `{"code": 409, "message": "Недостаточно средств"}`
  * `409 Conflict`: `{"code": 409, "message": "Конфликт nonce"}`
  * `409 Conflict`: `{"code": 409, "message": "Транзакция отклонена: неверный chainId"}`
  * `409 Conflict`: `{"code": 409, "message": "Транзакция отклонена: некорректный nonce"}`
  * `409 Conflict`: `{"code": 409, "message": "Транзакция отклонена: некорректное время"}`
  * `409 Conflict`: `{"code": 409, "message": "Транзакция отклонена: отправка самому себе"}`
  * `409 Conflict`: `{"code": 409, "message": "Транзакция отклонена: слишком длинный комментарий"}`
  * `409 Conflict`: `{"code": 409, "message": "Транзакция отклонена: некорректная сумма"}` / `"некорректная комиссия"`
  * `409 Conflict`: `{"code": 409, "message": "Транзакция отклонена: переполнение суммы и комиссии"}`
  * `409 Conflict`: `{"code": 409, "message": "Транзакция отклонена"}` (missing required fields)
  * `415 Unsupported Media Type`: `{"code": 415, "message": "Неподдерживаемый тип медиа"}` (non-JSON Content-Type)
  * `429 Too Many Requests`: `{"code": 429, "message": "Mempool переполнен"}` or rate-limiting threshold exceeded

---

### 6. View Transaction by Hash
* **URL**: `GET /api/v1/transactions/{txHash}`
  * `{txHash}` is strictly constrained to a 64-character hexadecimal pattern (`[a-fA-F0-9]{64}`), avoiding route collisions with `/transactions/*`.
* **Request Example**:
  ```bash
  curl http://localhost:8080/api/v1/transactions/c83b8a1e2d4...
  ```
* **Success Response (200 OK)**:
  ```json
  {
    "hash": "c83b8a1e2d4...",
    "from": "f5ox...",
    "to": "8fox...",
    "amount": 10000,
    "fee": 50,
    "comment": "Transfer to friend",
    "timestamp": 1730912500123,
    "status": "confirmed",
    "blockHeight": 14,
    "blockHash": "00a94bf1...",
    "confirmations": 2
  }
  ```
* **Errors**: `404 Not Found`: `{"code": 404, "message": "Транзакция отсутствует"}`

---

### 7. Get Block (by Height, Hash or 'latest')
Universal endpoint for inspecting chain blocks.

* **URL**: `GET /api/v1/blocks/{identifier}`
  * `{identifier}` accepts a numeric height (`0`, `1`, `42`), the keyword `latest`, or a 64-character block hash.
* **Request Example**:
  ```bash
  curl http://localhost:8080/api/v1/blocks/latest
  curl http://localhost:8080/api/v1/blocks/0
  ```
* **Success Response (200 OK)**:
  ```json
  {
    "height": 0,
    "hash": "e6a1...",
    "previousHash": "0",
    "merkleRoot": "0",
    "minerAddress": "GENESIS",
    "nonce": 0,
    "txCount": 0,
    "timestamp": 0,
    "transactions": []
  }
  ```
* **Errors**: `404 Not Found`: `{"code": 404, "message": "Блок отсутствует"}`

---

### 8. Network State and Sync Status
* **URL**: `GET /api/v1/network`
* **Request Example**:
  ```bash
  curl http://localhost:8080/api/v1/network
  ```
* **Success Response (200 OK)**:
  ```json
  {
    "chainId": "foxchain-main-1",
    "height": 42,
    "latestBlockHash": "0002ab1f592...",
    "peers": 4,
    "isSynced": true,
    "version": "1.0.0"
  }
  ```

---

## 💻 Desktop Web Client (tiny_pc_client)

The repository includes a standalone, ultra-lightweight SPA client (`tiny_pc_client`) running directly in the browser with zero Node.js, npm, or bundler dependencies:

```text
tiny_pc_client/
├── index.html       # Single HTML entry point (dark theme, responsive layout)
├── css/
│   └── style.css    # Custom lightweight styles without bulky UI frameworks
├── vendor/
│   └── mithril.min.js # Reactive micro-framework (8 KiB gzipped)
└── js/
    ├── crypto.js    # Web Crypto API module (Ed25519)
    ├── api.js       # HTTP client connecting to NodeChain REST API
    ├── state.js     # Centralized state manager with automated nonce pipelining
    └── app.js       # Interface components (Wallet, Mempool, Explorer, Dev Faucet)
```

### Key Client Features:
* 🔑 **100% Non-Custodial & Web Crypto API**: `Ed25519` key generation and storage operate strictly in the browser (`window.crypto.subtle`). Private keys never leave the user's machine.
* 📥 **One-Click Miner Wallet Import (`nodewallet.dat`)**: Paste the 3 lines from your node's `nodewallet.dat` directly into the import form for immediate access to mined blocks and pre-mined allocations.
* 🚀 **Nonce Pipelining**: Inspects the node's mempool and dynamically computes `nextNonce` factoring in pending outgoing transactions, enabling rapid transaction sequences without waiting for block confirmations.
* 🪙 **Dev Tool & Faucet**: Built-in developer console to dispense coins from the miner wallet and broadcast test stress-transactions across the network.
* 🔍 **Built-in Block & Tx Explorer**: Real-time lookups by block height or hash, detailed transaction views, and confirmation count tracking.
* 🌐 **Zero-Config Local Mode**: Can be opened directly from the file system (`file:///.../index.html`) or via any static file server (`python -m http.server 3000`) thanks to local CORS `originPatterns` support on the node.

---

## ⚙ Configuration and Environment Variables

NodeChain supports a two-tier configuration hierarchy:
1. Default values packaged inside `application.properties`.
2. User overrides in `./server.properties` (automatically generated on first run in working directory).
3. System environment variables or Spring Boot command line parameters.

### Available Configuration Parameters

| Parameter | Default | Description |
| :--- | :---: | :--- |
| **`server.port`** | `8080` | HTTP port for the client REST API. |
| **`node.p2p.port`** | `9090` | TCP port for incoming binary P2P connections. |
| **`node.db.path`** | `sqlite.db` | File path for the SQLite database. |
| **`blockchain.chain-id`** | `foxchain-main-1` | Unique network ID (peers with different IDs are dropped). |
| **`blockchain.difficulty`** | `4` | Base difficulty (required leading zeros in block hex PoW hash). |
| **`blockchain.difficulty-adjustment-interval`** | `10` | Difficulty retargeting interval (in blocks, Bitcoin-style). |
| **`blockchain.target-block-time-seconds`** | `10` | Target time per block (in seconds). |
| **`blockchain.min-difficulty`** | `1` | Minimum allowed difficulty for DAA. |
| **`blockchain.max-difficulty`** | `16` | Maximum allowed difficulty for DAA. |
| **`blockchain.mining-reward`** | `100000000` | Miner reward for minted block (in atomic units). |
| **`blockchain.auto-mine`** | `true` | Enables automatic background mining when mempool has txs. |
| **`blockchain.mine-interval-seconds`**| `10` | Mempool poll and mine attempt interval (in seconds). |
| **`blockchain.max-mempool-size`** | `10000` | Maximum number of pending transactions in memory. |
| **`blockchain.genesis-allocation`** | `1000000000000` | Initial Genesis allocation (10,000 FOXT in atomic units). |
| **`blockchain.genesis-address`** | *(empty)* | Target pre-mine address (defaults to node wallet `nodewallet.dat`). |
| **`blockchain.halving-interval`** | `210000` | Halving period in blocks (reward halves every N blocks). |
| **`node.p2p.seeds`** | *(empty)* | Comma-separated seed peers: `127.0.0.1:9091,192.168.1.50:9090`. |

> **Example `server.properties` for a second local node:**
> ```properties
> server.port=8081
> node.p2p.port=9091
> node.db.path=node2.db
> node.p2p.seeds=127.0.0.1:9090
> ```

---

## 🚀 Quick Start

### Requirements
* **JDK 17+** (Eclipse Temurin, OpenJDK or Oracle)
* **Apache Maven 3.8+**

### Building from Source
Clone the repository and build an executable Fat JAR:

```bash
# Clone
git clone https://github.com/foxso/nodechain.git
cd nodechain

# Build without tests
mvn clean package -DskipTests

# Run full integration and consensus test suite
mvn test
```
The compiled JAR is generated at `target/node.jar`.

---

### Running a Single Node

```bash
mkdir -p testnet && cd testnet
cp ../target/node.jar .

java -jar node.jar
```

On first launch, the node executes the following bootstrap sequence:
1. Generates `server.properties` with default overrides if missing.
2. Creates an Ed25519 keypair and saves the miner wallet to `nodewallet.dat`.
3. Initializes SQLite schema in `sqlite.db` and writes deterministic Genesis Block #0.
4. Opens binary P2P server on port `9090` and REST API on port `8080`.
5. Starts the auto-miner service assembling pending transactions into blocks.

Verify the node status:
```bash
curl http://localhost:8080/api/v1/network
```

---

### Running a Local Network (Multi-Node Testnet)

Launch two nodes on a single host that discover, authenticate, and sync with each other:

```bash
# Terminal 1: Seed Node
mkdir -p testnet/node1 && cd testnet/node1
cp ../../target/node.jar .
java -jar node.jar --server.port=8080 --node.p2p.port=9090 --node.db.path=node1.db
```

```bash
# Terminal 2: Peer Node
mkdir -p testnet/node2 && cd testnet/node2
cp ../../target/node.jar .
java -jar node.jar \
  --server.port=8081 \
  --node.p2p.port=9091 \
  --node.db.path=node2.db \
  --node.p2p.seeds=127.0.0.1:9090
```

Within a second, both consoles will report mutual cryptographic handshake verification:
```text
Registered incoming P2P peer connection: 127.0.0.1:9091
P2P peer verified & handshake confirmed for node f5ox...
```

---

## 🛠 Key Generation and Transaction Submission (CLI & Python)

Because NodeChain nodes operate on a non-custodial model, transactions must be signed client-side.

Below is a complete **Python 3** script that generates an Ed25519 keypair, calculates the fox address, signs a transaction canonically, and submits it to the node REST API:

<details>
<summary>▶ Show transaction submission script (<code>wallet_client.py</code>)</summary>

```python
import base64
import hashlib
import json
import urllib.request
from cryptography.hazmat.primitives.asymmetric import ed25519
from cryptography.hazmat.primitives import serialization

# 1. Generate Ed25519 Keypair
private_key = ed25519.Ed25519PrivateKey.generate()
public_key = private_key.public_key()

# 2. Export public key to Base64 (X.509 SPKI DER)
pub_der = public_key.public_bytes(
    encoding=serialization.Encoding.DER,
    format=serialization.PublicFormat.SubjectPublicKeyInfo
)
pub_b64 = base64.b64encode(pub_der).decode("utf-8")

# 3. Derive Fox Address
raw_sha = hashlib.sha256(pub_der).digest()
addr_chars = list(hashlib.sha256(pub_der).hexdigest())

pos_f = raw_sha[0] % 5
pos_o = raw_sha[1] % 5
while pos_o == pos_f:
    pos_o = (pos_o + 1) % 5
pos_x = raw_sha[2] % 5
while pos_x == pos_f or pos_x == pos_o:
    pos_x = (pos_x + 1) % 5

addr_chars[pos_f] = 'f'
addr_chars[pos_o] = 'o'
addr_chars[pos_x] = 'x'
sender_address = "".join(addr_chars).lower()

print(f"[*] Generated Fox Address: {sender_address}")

# 4. Build Transaction
chain_id = "foxchain-main-1"
recipient_address = "fox0000000000000000000000000000000000000000000000000000000000000" # valid recipient
amount = 10000
fee = 50
nonce = 0
comment = "Hello from Python Client!"

# Canonical signing string with chainId (Cross-Chain Replay protection)
clean_comment = comment.strip() if comment else ""
canonical = (
    f"{chain_id}|{sender_address}|{recipient_address}|{amount}|{fee}|{nonce}|{clean_comment}"
    if clean_comment
    else f"{chain_id}|{sender_address}|{recipient_address}|{amount}|{fee}|{nonce}"
)

# 5. Sign with Ed25519 (RFC 8032)
signature_bytes = private_key.sign(canonical.encode("utf-8"))
sig_b64 = base64.b64encode(signature_bytes).decode("utf-8")

payload = {
    "chainId": chain_id,
    "from": sender_address,
    "to": recipient_address,
    "amount": amount,
    "fee": fee,
    "nonce": nonce,
    "publicKey": pub_b64,
    "signature": sig_b64,
    "comment": clean_comment
}

# 6. Submit to node via REST API
req = urllib.request.Request(
    "http://localhost:8080/api/v1/transactions/send",
    data=json.dumps(payload).encode("utf-8"),
    headers={"Content-Type": "application/json"}
)

try:
    with urllib.request.urlopen(req) as resp:
        print("[+] Node response:", resp.read().decode("utf-8"))
except urllib.error.HTTPError as e:
    print(f"[-] Submission error ({e.code}):", e.read().decode("utf-8"))
```
</details>

---

## 🛡 Security Mechanisms

NodeChain implements algorithmic defenses against common blockchain attacks:

1. **Transaction Malleability Protection**:
   Signatures are applied strictly over a canonical delimiter-separated argument string (`chainId|from|to|amount|fee|nonce|comment.trim()`). No extraneous metadata, whitespace injection, or altered parameters can pass validation without breaking the Ed25519 signature.
2. **DoS Mempool Eviction Protection (Tail Nonce Rule)**:
   When reaching `maxMempoolSize`, transactions with lower fees can only be evicted if they are **tail nonces**. Evicting `nonce 0` is prohibited if dependent transactions (`nonce 1`, `nonce 2`) exist in mempool. Accounts with zero balance cannot displace valid transactions.
3. **Network GZIP Bomb Defense & Frame Size Ceilings**:
   `NodePacket.decompressGzip` enforces a strict decompression threshold. If uncompressed stream exceeds 4 MiB, decompression aborts immediately with an I/O exception. Inbound network frames are bound by strict size limits: up to 16 bytes for PING/PONG, up to 64 KiB for control packets, and up to 4 MiB for block batches.
4. **Arithmetic Overflow Protection**:
   All balance additions and subtractions for transactions, fees, and mining rewards utilize `Math.addExact()` and `Math.subtractExact()`. Negative values or inputs overflowing `Long.MAX_VALUE` trigger consensus errors.
5. **Timestamp Drift & Median-Time-Past (MTP-11)**:
   A median filter over the previous 11 blocks (**Median-Time-Past MTP-11**, Bitcoin Core model) enforces that each block's timestamp must be strictly greater than the median of the prior 11 blocks (`next.timestamp > mtp`). In addition, timestamps cannot exceed the node's local time by more than **30 seconds** (tightened from 10 minutes), neutralizing Time Warp difficulty manipulation.
6. **Per-IP Rate Limiting with Localhost Exemption**:
   Incoming network packets are rate-limited per time window for each `IP:opcode` pair from external hosts. Local addresses (`127.0.0.1`, `localhost`, `::1`) are exempt from limits to enable uninterrupted multi-node cluster testing. Memory tables in P2P (`seenHandshakeNonces > 2000`) and REST API (`lastRestTxTime > 1000`) automatically prune stale entries to prevent out-of-memory DoS.
7. **Global Exception Handling & 500 Immunity (API Robustness)**:
   All HTTP requests pass through `GlobalExceptionHandler`. Syntax errors in JSON payloads, type mismatches, invalid Content-Types, or unsupported HTTP methods return strict client errors (`400 Bad Request`, `405 Method Not Allowed`, `415 Unsupported Media Type`, `409 Conflict`), preventing uncaught `500 Internal Server Error` responses.
8. **Node Wallet Integrity Self-Test (Challenge-Response)**:
   During bootstrap, `NodeWalletService` validates the Ed25519 keypair by signing a test payload (`challenge-nodewallet.dat`). Any file corruption or key mismatch halts node startup prior to opening network ports, preventing miner reward loss.
9. **Anti-Float Coercion Protection**:
   Jackson deserialization disables float-to-integer coercion (`featuresToDisable(ACCEPT_FLOAT_AS_INT)` and `spring.jackson.deserialization.accept-float-as-int=false`). Fractional inputs in amounts or fees are rejected immediately with `400 Bad Request`.
10. **Reorganization Depth Ceiling & Step-Back Loop Guard (`MAX_REORG_DEPTH = 20`)**:
    Chain reorganization depth is capped at **20 blocks** (reduced from 100) to protect the network from deep reorganizations, forks, and double-spending. If a peer fails to locate a common ancestor after 10 consecutive step-back requests (`peerStepBackCount > 10`), the connection is forcibly terminated.
11. **Merkle Second-Preimage Collision Defense (RFC 6962 Domain Separation)**:
    Transaction leaves are hashed with prefix `00` (`H_leaf = SHA256("00" + tx.hash)`), and internal nodes with prefix `01` (`H_parent = SHA256("01" + H_left + H_right)`), mathematically preventing intermediate nodes from being presented as leaf transactions.
12. **Genesis Block & Pre-mine Protection (`adoptPeerGenesisBlock` / `adoptPeerGenesisChain`)**:
    A clean node accepts a peer's genesis block only if `blockchain.genesis-address` is explicitly configured in local properties and matches the incoming genesis miner address. Foreign unverified genesis blocks are rejected immediately, preventing Genesis Hijack.
13. **Bitcoin DAA & Cumulative ChainWork ($16^{\text{difficulty}}$)**:
    Dynamic difficulty retargeting every 10 blocks according to actual block production intervals. Fork cumulative work is computed as the exact sum of $16^{\text{difficulty}}$ across all blocks in the branch. Blocks carrying forged or mismatched difficulty are blocked before storage.
14. **Cross-Chain Replay Immunity via Strict Chain ID**:
    Network identifier (`chainId`) is strictly bound to canonical signature payloads, transaction hash calculations, REST API payloads, P2P handshakes, and **directly embedded into the Block header structure, SHA-256 block hash calculation, and database schema**, preventing cross-chain block replaying.
15. **Dynamic `isSynced` State Computation**:
    Remote peer heights are tracked per `PeerConnection` instance, and synchronization status evaluates only currently active connections. Handshake height spoofing no longer causes persistent desync state.
16. **Reorg Pre-Validation & Fast-Sync Pagination**:
    Branch PoW and headers are verified prior to executing rollback queries against SQLite. `BLOCKS_DATA` frames are limited to 100 blocks with automated pagination during initial chain download.
17. **Transaction History Anti-Poisoning**:
    Transactions carrying negative nonces (`nonce < 0`) or abnormal timestamps ($timestamp \le 0$ or $> \text{now} + 600\text{s}$) are rejected in early formatting checks before invoking heavy Ed25519 signature verification.
18. **Peer Table Protection & Concurrency Ceilings**:
    The `peers` table is capped at 256 records, unreachable nodes are immediately pruned upon connection failure (`peerRepository.delete(host, port)`), and outbound connection attempts are restricted to at most 32 concurrent peers.
19. **P2P Slowloris DoS Defense (Frame Read Deadlines)**:
    Socket timeout is reduced to 10 seconds. Inbound frame reads in `NodePacket.readFullyWithTimeout` enforce a strict 10-second completion deadline, disconnecting peers that trickle bytes slowly to exhaust worker threads.
20. **Eclipse Attack Mitigation & Inbound Connection Quotas**:
    Inbound connections per external IP address are capped at 2. Total inbound sockets are bounded by `MAX_INBOUND_CONNECTIONS = 48` within the 64-slot limit, reserving at least 16 outbound slots exclusively for honest peer nodes.
21. **SSRF & Peer Poisoning Network Filtering**:
    `P2pClientManager.isSafePeerAddress` blocks connections to privileged ports (< 1024), sensitive infrastructure ports (22, 23, 25, 80, 443, 3306, 5432, 6379, 27017, 11211, 8080), RFC 1918 private subnets (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), broadcast IPs, and cloud metadata ranges (`169.254.0.0/16`), while allowing localhost for isolated testing clusters.
22. **Fox Address CRC32 Checksum Validation**:
    The last 8 hex characters of each address encode a standard IEEE 802.3 CRC32 checksum over the first 56 characters, catching typos and invalid addresses at `isValidAddress()` before network broadcast or database persistence.
23. **Anti-Spam Minimum Relay Fee (`MIN_RELAY_FEE = 1L`)**:
    Transactions must supply at least 1 atomic unit in fees. Zero-fee spam transactions are rejected at the mempool gate with `409 Conflict`.
24. **Concurrent SQLite WAL Connection Pool (HikariCP Pool = 10 + connectionInitSql)**:
    Database connection pool is configured to 10 connections with `PRAGMA busy_timeout = 10000;` guaranteed on all pooled connections via `connectionInitSql`, enabling parallel non-blocking read access for REST API endpoints alongside SQLite WAL writes.
25. **Linear Mempool Eviction $O(N)$ (Anti-Algorithmic DoS)**:
    The lowest-fee mempool eviction algorithm (`evictLowestFeeTx`) is optimized to linear time $O(N)$ using sender tail nonce indexing, eliminating $O(N^2)$ node stalls under global lock when the mempool reaches capacity (10,000 txs).
26. **Strict MTP-11 Fork Consensus (Anti-Time-Warp)**:
    Reorganization branch header validation in `validateBranch` strictly enforces the median timestamp of the previous 11 blocks (`getBranchMedianTimePast`), preventing timestamp manipulation to artificially lower difficulty.
27. **Miner Wallet Key Protection (POSIX 0600)**:
    Miner keys in `nodewallet.dat` are saved with restrictive file permissions `0600` (`rw-------`) on POSIX filesystems, preventing unauthorized local users from reading private keys.
28. **Strict CORS & Unified Jackson Deserialization**:
    Wildcard CORS `*` is disabled in REST API (restricted to localhost, 127.0.0.1, and null for local clients), and internal P2P Jackson deserializer disables `ACCEPT_FLOAT_AS_INT` identically to the REST API.

---

## 🧪 Testing

The codebase is covered by comprehensive integration and fuzzing suites (totaling **81 tests** passing with 100% success in ~11 seconds):

```bash
# Run all project tests
mvn test
```

### Key Test Suites:
* `BlockchainIntegrationTest.java` (21 tests):
  * Comprehensive testing of all 8+ REST API endpoints via Spring `MockMvc`.
  * Complete lifecycle: transaction submission $\rightarrow$ mining $\rightarrow$ balance and nonce progression.
  * Automatic chain reorganization when encountering a fork with higher chainwork.
  * Verification that unconfirmed incoming funds cannot be spent.
  * Transaction fee-priority sorting in miner.
  * Mempool Pipelining: sequential broadcast of chained transactions with ascending nonces.
* `ClientApiFuzzAndRobustnessTest.java` (13 tests, 50+ fuzzing scenarios):
  * Extensive fuzzing and edge-case testing of the REST API against malformed or hostile inputs.
  * Numerical overflow, floating-point inputs, empty bodies, and missing fields.
  * Boundary limits (pagination clamps, comment length $>256$ chars, case-insensitivity of addresses).
  * Injection attempts (SQLi, null bytes) and unsupported HTTP methods / Content-Types.
  * Confirmed API resilience: zero crashes into `500 Internal Server Error`.
* `ClaudeReviewVerificationTest.java` (9 tests):
  * Comprehensive verification of security audit findings and architectural fixes.
  * Peer genesis block adoption for fresh nodes and protection against pre-mine tampering.
  * Cross-Chain Replay analysis and chain domain separation.
  * Overflow-safe ChainWork calculations with static difficulty.
  * P2P request rate limiting for external IPs and localhost exemption.
  * Handshake replay protection via strict `sessionNonce`.
  * DoS defense: fast transaction format validation before heavy digital signature checks.
  * Merkle Tree: RFC 6962 strict domain separation for leaves (`00`) and internal nodes (`01`).
  * Ed25519: compact keys (44 bytes DER / 60 Base64) and signatures (64 bytes DER / 88 Base64).
  * Safe history step-back mathematics (`-20`).
* `MultiNodeNetworkTest.java` (10 tests):
  * Integration tests of a real P2P cluster composed of 2 and 3 independent nodes running on distinct ports and SQLite databases.
  * Mutual authentication and cryptographic handshake with Ed25519 signatures.
  * Mempool transaction synchronization via Gossip (`NEW_TX_ANNOUNCE`).
  * Real-time propagation of newly mined blocks (`NEW_BLOCK_ANNOUNCE`).
  * Fast-Sync (Initial Block Download, IBD) for joining nodes using batched `GET_BLOCKS`.
  * Automatic fork reorganization (Chain Reorg) based on cumulative chainwork.
  * Byzantine resilience: isolation and rejection of peers broadcasting invalid blocks.
  * Multi-hop mesh relaying through intermediate nodes.
  * Peer discovery via `GET_PEERS` / `PEERS_LIST` and recovery after disconnects.
  * Isolation of networks with mismatching `chainId`.
* `ConsensusAndSecurityTest.java` (16 tests):
  * Miner thread interruption and graceful termination.
  * In-block duplicate transaction rejection.
  * GZIP bomb defense and unknown packet flag handling.
  * P2P handshake replay window and unauthorized packet rejection.
  * Wallet file corruption recovery and challenge validation.
  * Bitcoin Core subsidy halving schedule verification.
  * Tail nonce eviction rules in saturated mempools.
* `NewPlanSecurityAndDaaTest.java` (8 tests):
  * Bitcoin DAA: difficulty increase/decrease based on block production pace.
  * Exact cumulative chainwork summation as $\sum 16^{\text{difficulty}}$.
  * Rejection of spoofed, zero, or understated difficulty blocks.
  * Chain ID domain separation in canonical signatures and cross-chain isolation.
  * Cascading mempool eviction on Nonce Gap.
  * OOM prevention against oversized packet frames.
  * Early rejection of negative nonces and out-of-bounds timestamps.
* `CryptoUtilTest.java` (2 tests):
  * Correctness of 64-character fox-address generation and brand character placement.
  * Ed25519 (RFC 8032) signature generation and verification.
* `NodePacketTest.java` (2 tests):
  * Binary framing serialization and deserialization with MAGIC.
  * Automatic GZIP compression threshold for payloads $>128$ bytes.

---

## 📂 Project Structure

```text
nodechain/
├── pom.xml                   # Maven build configuration (Java 17, Spring Boot 3.3.5)
├── README.md                 # Project documentation (Russian)
├── README.EN.md              # Project documentation (English)
├── tiny_pc_client/           # Standalone web client on Mithril.js (Ed25519 SPA)
│   ├── index.html            # Single HTML entry point (dark theme, responsive)
│   ├── css/
│   │   └── style.css         # Custom lightweight styling
│   ├── vendor/
│   │   └── mithril.min.js    # Reactive micro-framework (8 KiB gzipped)
│   └── js/
│       ├── crypto.js         # Web Crypto API module (Ed25519)
│       ├── api.js            # NodeChain REST API client
│       ├── state.js          # Reactive state store with nonce pipelining
│       └── app.js            # UI components (Wallet, Mempool, Explorer, Faucet)
├── src/main/java/so/fox/nodechain/
│   ├── NodechainApplication.java   # Spring Boot entry point
│   ├── api/
│   │   ├── ApiController.java      # REST API v1 public endpoints
│   │   └── GlobalExceptionHandler.java # API error handler (400, 405, 415)
│   ├── config/
│   │   ├── DatabaseConfig.java     # SQLite WAL DataSource & HikariCP
│   │   └── NodeConfig.java         # Node parameters and consensus validation
│   ├── core/
│   │   ├── BlockchainService.java  # Mempool, mining, forks, and balances
│   │   ├── ConsensusUtil.java      # Consensus rules, PoW, and Merkle tree
│   │   ├── NodeWalletService.java  # Ed25519 wallet management and challenge test
│   │   └── Result.java             # Monolithic typed result (Errors as Values)
│   ├── crypto/
│   │   └── CryptoUtil.java         # Ed25519, SHA-256, and fox addressing
│   ├── model/
│   │   ├── Account.java            # Account state model (balance, nonce)
│   │   ├── Block.java              # Block model (header, PoW, Merkle root)
│   │   ├── PeerInfo.java           # Network peer metadata
│   │   └── Transaction.java        # Transaction model, canonical hash, sig
│   ├── p2p/
│   │   ├── P2pClientManager.java   # TCP server, Gossip, and chain sync
│   │   ├── PeerConnection.java     # Active TCP peer connection
│   │   └── packet/
│   │       ├── NodePacket.java     # Binary frame with GZIP compression
│   │       └── OpCode.java         # Protocol command operation codes
│   └── repository/
│       ├── AccountRepository.java  # Account balances and nonces in SQLite
│       ├── BlockRepository.java    # Chain blocks repository in SQLite
│       ├── PeerRepository.java     # Discovered peers table
│       └── TransactionRepository.java # Transaction history and mempool
├── src/main/resources/
│   └── application.properties      # Default application properties
└── src/test/java/so/fox/nodechain/
    ├── BlockchainIntegrationTest.java      # API, mining, and reorg tests
    ├── ClaudeReviewVerificationTest.java   # Security audit verification tests
    ├── ClientApiFuzzAndRobustnessTest.java # API fuzzing and 500 error defense
    ├── ConsensusAndSecurityTest.java       # Security, P2P, and PoW tests
    ├── CryptoUtilTest.java                 # Crypto and address unit tests
    ├── MultiNodeNetworkTest.java           # Multi-node P2P cluster tests
    ├── NewPlanSecurityAndDaaTest.java      # DAA, ChainWork, and Nonce tests
    └── NodePacketTest.java                 # Binary protocol framing tests
```

---

<p align="center">
  Crafted with ❤️ and attention to detail. No clutter, only clean architecture and robust code.
</p>
