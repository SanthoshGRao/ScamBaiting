import os
import json
import re
import pandas as pd
import numpy as np
import tensorflow as tf
import keras
from keras.models import Sequential
from keras.layers import Input, Embedding, GlobalAveragePooling1D, Dense

def train_tensorflow_lite_model():
    print("Initiating ScamShield Native TFLite Training Pipeline...")
    
    # 1. Load the generated dataset
    dataset_path = os.path.join("dataset", "indian_scam_corpus_v1.csv")
    if not os.path.exists(dataset_path):
        print(f"Error: Dataset not found at {dataset_path}")
        return
        
    print("Loading dataset...")
    df = pd.read_csv(dataset_path)
    
    if 'text' not in df.columns or 'category' not in df.columns:
        print("Error: Dataset must contain 'text' and 'category' columns.")
        return
        
    texts = df['text'].values
    labels = (df['category'].str.lower() == 'scam').astype(int).values

    # 2. Tokenize text using pure Python (avoids Keras preprocessing IDE errors)
    print("Tokenizing text...")
    vocab_size = 10000
    max_length = 256
    oov_tok = "<oov>"
    
    word_counts = {}
    for text in texts:
        words = re.sub(r'[^a-z0-9 ]', '', str(text).lower()).split()
        for word in words:
            word_counts[word] = word_counts.get(word, 0) + 1
            
    sorted_words = sorted(word_counts.items(), key=lambda x: x[1], reverse=True)
    
    word_index = {oov_tok: 1}
    for i, (word, count) in enumerate(sorted_words):
        if i + 2 >= vocab_size:
            break
        word_index[word] = i + 2
        
    sequences = []
    for text in texts:
        words = re.sub(r'[^a-z0-9 ]', '', str(text).lower()).split()
        seq = [word_index.get(word, 1) for word in words]
        sequences.append(seq)
        
    # Pad sequences (post-padding)
    padded_sequences = np.zeros((len(sequences), max_length), dtype=np.int32)
    for i, seq in enumerate(sequences):
        length = min(len(seq), max_length)
        padded_sequences[i, :length] = seq[:length]

    os.makedirs("models", exist_ok=True)
    vocab_path = os.path.join("models", "vocab.json")
    with open(vocab_path, 'w') as f:
        json.dump(word_index, f)
    print(f"✅ Vocabulary saved to {vocab_path}")

    # 3. Build the Model
    print("Building and training Keras Model...")
    model = Sequential([
        Input(shape=(max_length,)),
        Embedding(vocab_size, 16),
        GlobalAveragePooling1D(),
        Dense(16, activation='relu'),
        Dense(1, activation='sigmoid')
    ])
    
    model.compile(loss='binary_crossentropy', optimizer='adam', metrics=['accuracy'])
    
    model.fit(padded_sequences, labels, epochs=10, batch_size=16, validation_split=0.2)

    # 4. Export to TensorFlow Lite
    print("\nExporting model to TensorFlow Lite format...")
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite_model = converter.convert()
    
    out_path = os.path.join("models", "scam_detector.tflite")
    with open(out_path, 'wb') as f:
        f.write(tflite_model)
        
    print(f"\n✅ Success: TensorFlow Lite model exported to: {out_path}")
    print("Place 'scam_detector.tflite' and 'vocab.json' in the Android app's 'assets' folder!")

if __name__ == "__main__":
    train_tensorflow_lite_model()
