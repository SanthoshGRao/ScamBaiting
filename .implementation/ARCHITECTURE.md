# System Architecture

## Pattern

Agent-Based Modular Architecture

## Flow

User Input → DetectionAgent → StrategyAgent → ResponseAgent → Output

## Key Design Principles

* Loose coupling between agents
* Clear input/output contracts
* Replaceable LLM component
* Logging at every stage

## Data Flow

1. Input captured
2. DetectionAgent processes input
3. StrategyAgent selects response strategy
4. ResponseAgent generates reply
5. System logs interaction
