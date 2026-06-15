import os
import pandas as pd
import random
from faker import Faker  # type: ignore

fake = Faker('en_IN') # Using Indian locale for names, addresses, phone numbers

def generate():
    print("Generating Indian context scam dataset...")
    
    # Scam Templates
    scam_templates = [
        "Dear customer, your SBI account {account} will be blocked today due to pending KYC. Please update PAN immediately: {link}",
        "URGENT: Your FedEx package {tracking} is on hold due to unpaid customs fee of ₹{amount}. Pay here to release: {link}",
        "TRAI Alert: Your mobile number {phone} will be disconnected in 2 hours for illegal activity. Call {trai_num} or press 9.",
        "Congratulations! You have won ₹{prize} in the Jio lucky draw. Send ₹{fee} registration fee to UPI: {upi} to claim.",
        "Your Electricity power will be disconnected at 9:30 PM tonight from the main office. Pay your previous month bill: {link}",
        "HR Team: Your resume has been selected for remote data entry job. Rs 2000/day. Message on Whatsapp: {link}",
        "Emergency: Hi I am in hospital right now with an accident. Please pay ₹{amount} immediately via Google Pay to {upi}.",
        "[HDFC Bank] Your NetBanking is temporarily disabled. Click {link} to verify your identity.",
        "Income Tax Dept: Your refund of ₹{prize} has been approved. Claim your refund instantly here: {link}",
        "Sir we are calling from Amazon customer support. Someone purchased iPhone 15 from your card. Call us at {phone} to cancel."
    ]
    
    # Safe Templates
    safe_templates = [
        "Hey {name}, are we still meeting for chai at {time}?",
        "Please send the Q3 report by EOD. Thanks.",
        "Bhai, Swiggy order has arrived. Come down.",
        "Remember to pick up groceries on your way back home.",
        "Your Amazon package {tracking} is out for delivery today.",
        "Meeting is scheduled for tomorrow at 10 AM in the main conference room.",
        "Can you call me when you have a minute? Need to discuss the presentation.",
        "Happy Birthday {name}! Wishing you a great year ahead.",
        "The flight 6E-{amount} is delayed by 30 mins due to weather.",
        "Mom said dinner is ready. Come to the dining table."
    ]

    data = []
    
    # Generate 175 scams
    for i in range(175):
        template = random.choice(scam_templates)
        msg = template.format(
            account=fake.bban()[-4:], 
            link=f"http://{fake.domain_word()}-update.com/kyc",
            tracking=f"FDX{random.randint(1000,9999)}",
            amount=random.randint(50, 5000),
            phone=fake.phone_number(),
            trai_num="1800-11-2233",
            prize=random.randint(10000, 500000),
            fee=random.randint(100, 1500),
            upi=f"{fake.user_name()}@ybl",
            name=fake.first_name()
        )
        data.append({"message_id": f"msg_{len(data)}", "text": msg, "label": 1, "category": "scam"})

    # Generate 175 safe messages
    for i in range(175):
        template = random.choice(safe_templates)
        msg = template.format(
            name=fake.first_name(),
            time=f"{random.randint(1,12)} PM",
            tracking=f"AMZ{random.randint(1000,9999)}",
            amount=random.randint(100, 999)
        )
        data.append({"message_id": f"msg_{len(data)}", "text": msg, "label": 0, "category": "safe"})

    # Shuffle dataset
    random.shuffle(data)
    
    df = pd.DataFrame(data)
    
    os.makedirs("dataset", exist_ok=True)
    out_path = os.path.join("dataset", "indian_scam_corpus_v1.csv")
    df.to_csv(out_path, index=False)
    print(f"Success: Generated {len(df)} samples at {out_path}")

if __name__ == "__main__":
    generate()
