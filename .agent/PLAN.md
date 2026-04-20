# System Architecture Plan

## Core Architecture

Agent-Based Modular System

## Modules

### 1. DetectionAgent

* Input: text, email, notifications
* Methods:

  * Rule-based keyword detection
  * LLM-based classification
* Output:

  * Scam probability
  * Scam category

### 2. StrategyAgent

* Input: Detection output
* Logic:

  * Risk-based decision making
  * Strategy selection:

    * Confusion
    * Delay
    * Fake compliance
* Output:

  * Response strategy

### 3. ResponseAgent

* Input: Strategy + context
* Uses LLM to generate:

  * Human-like scam-bait responses
  * Persona-based replies

### 4. Interface Layer

* Notification listener
* Chat interface
* Dashboard

### 5. Data Layer

* Scam dataset
* Logs of conversations
* Strategy effectiveness tracking

## Phases

### Phase 1: Core Detection System

### Phase 2: Strategy Engine

### Phase 3: Response Generation

### Phase 4: Integration

### Phase 5: Testing & Optimization
