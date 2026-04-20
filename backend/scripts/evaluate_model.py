import os
import pandas as pd
import seaborn as sns
import matplotlib.pyplot as plt
from sklearn.metrics import classification_report, confusion_matrix, accuracy_score, precision_score, recall_score, f1_score
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import train_test_split

def evaluate():
    print("Initiating ScamShield Evaluation Pipeline...")
    
    dataset_path = os.path.join("dataset", "indian_scam_corpus_v1.csv")
    if not os.path.exists(dataset_path):
        print(f"Error: Dataset not found at {dataset_path}")
        return
        
    df = pd.read_csv(dataset_path)
    
    # We use a TF-IDF + Logistic Regression baseline as our offline fast-eval model.
    # In full production, this would be swapped with the Groq API results array.
    vectorizer = TfidfVectorizer(max_features=1000, ngram_range=(1, 2))
    X = vectorizer.fit_transform(df['text'])
    y = df['label']
    
    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.3, random_state=42)
    
    model = LogisticRegression(class_weight='balanced')
    model.fit(X_train, y_train)
    
    y_pred = model.predict(X_test)
    
    print("\n--- Model Evaluation Metrics ---")
    print(f"Accuracy:  {accuracy_score(y_test, y_pred):.4f}")
    print(f"Precision: {precision_score(y_test, y_pred):.4f}")
    print(f"Recall:    {recall_score(y_test, y_pred):.4f}")
    print(f"F1-Score:  {f1_score(y_test, y_pred):.4f}\n")
    
    print("Classification Report:")
    print(classification_report(y_test, y_pred, target_names=['Safe', 'Scam']))
    
    # Generate Confusion Matrix
    cm = confusion_matrix(y_test, y_pred)
    plt.figure(figsize=(8, 6))
    sns.heatmap(cm, annot=True, fmt='d', cmap='Reds', xticklabels=['Safe', 'Scam'], yticklabels=['Safe', 'Scam'])
    plt.title('ScamShield Baseline Detection Confusion Matrix')
    plt.xlabel('Predicted Label')
    plt.ylabel('True Label')
    
    os.makedirs("docs", exist_ok=True)
    out_path = os.path.join("docs", "confusion_matrix.png")
    plt.savefig(out_path, dpi=300, bbox_inches='tight')
    plt.close()
    print(f"Success: Generated confusion matrix at {out_path}")

if __name__ == "__main__":
    evaluate()
