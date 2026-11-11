# ⛓️ BlockchainIST — a distributed ledger in Java + gRPC

> **TL;DR:** A simplified **blockchain** for digital wallets: users create wallets and transfer money, several **replicated servers** (nodes) keep a copy of all balances, and a central **sequencer** puts every transaction in the same order and groups them into **blocks**, so all copies stay identical. Requests are **digitally signed** (RSA) so nobody can move money from someone else's wallet. Built in **Java** with **gRPC**. University project for *Distributed Systems* at Instituto Superior Técnico.

---

## What is this, in plain words?

A bank that keeps its accounts on **one** computer has a problem: if that computer fails, everything stops. The solution is to keep **copies on several computers**. But then a new problem appears: if two transfers arrive at the same time, different copies might apply them in a different order and end up with **different balances**.

This project solves that with an architecture used by many real blockchains and databases:

```
            ┌──────────────┐
  client ──►│    node A    │──┐
            └──────────────┘  │   "please order this transaction"
            ┌──────────────┐  ├──────────────►┌─────────────┐
  client ──►│    node B    │──┤               │  sequencer  │  groups transactions
            └──────────────┘  │◄──────────────└─────────────┘  into numbered blocks
            ┌──────────────┐  │   "here is block #N"
  client ──►│    node C    │──┘
            └──────────────┘
   every node applies the same blocks in the same order → same balances everywhere
```

- **Client:** a command-line program where a user creates/deletes wallets, checks balances and makes transfers.
- **Nodes:** servers that hold the wallets. Each belongs to an organisation (OrgA, OrgB, OrgC). They forward transactions to the sequencer and apply the blocks they receive.
- **Sequencer:** gives a global order to all transactions and closes a block every **N transactions** or every **T seconds**.

## What we implemented

- **Replicated nodes** that keep themselves up to date by continuously pulling new blocks from the sequencer.
- **Block-based broadcast:** the sequencer groups transactions into blocks instead of sending them one by one.
- **Duplicate detection:** each request has an ID, so a transaction retried by the client is not applied twice.
- **Blocking and non-blocking commands** in the client (`C`/`c`, `E`/`e`, `S`/`s`, `T`/`t`): the lower-case versions return immediately and the answer arrives later (asynchronous gRPC).
- **Digital signatures:** clients sign their requests with their private key and nodes verify them with the user's public key; blocks are signed by the sequencer.
- **Fault-injection helper:** a gRPC interceptor that delays a node on purpose, to test slow-server scenarios.
- Not completed: automatic client failover when a node crashes (see the self-assessment in [`docs/`](docs/)).

## How to run it

You need **Java 17+**, **Maven** and **OpenSSL**.

```bash
# 1. Generate demo keys for the test users (Alice, Bob, ..., Iris) and the sequencer
./generate-keys.sh

# 2. Build everything
mvn clean install

# 3. In separate terminals, start the sequencer, a node and a client
cd sequencer && mvn exec:java
cd node      && mvn exec:java
cd client    && mvn exec:java -Duser.id=Alice
```

Default ports and arguments are defined in each module's `pom.xml`. Example client session:

```
C Alice cA 0 0        # Alice creates wallet "cA" via node 0, no artificial delay (blocking)
S cA 0 0              # show the balance of wallet "cA"
X                     # exit
```

Automatic tests (client input → expected output) are in [`tests/`](tests/): run `tests/run_tests.sh` with a sequencer and node running.

## Project structure

```
contract/    # gRPC service definitions (.proto) + RSA signing helpers
sequencer/   # Orders transactions and builds blocks
node/        # Holds wallets, verifies signatures, applies blocks
client/      # Command-line client (blocking + async commands)
tests/       # Input/expected-output test files
docs/        # Our delivery notes / self-assessment (in Portuguese)
generate-keys.sh
```

## Authors

Vasco Caetano and group colleagues, Instituto Superior Técnico.

## Context

- **Course:** Distributed Systems (Sistemas Distribuídos), Instituto Superior Técnico, University of Lisbon
- **Stack:** Java 17, gRPC, Protocol Buffers, Maven, RSA (SHA256withRSA)
- The project statement and the initial code skeleton were provided by the course teaching staff.
