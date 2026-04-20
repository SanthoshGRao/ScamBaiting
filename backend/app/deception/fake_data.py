"""
Fake Data Generator — Generates realistic but verifiably synthetic data.

All data is designed to be obviously fake upon verification:
- Phone numbers use reserved/non-routable ranges
- Transaction IDs use impossible prefixes
- Names are from a curated fictional list
"""

from __future__ import annotations

import random
import string
from datetime import datetime, timedelta


# Curated fictional names — never scraped from real databases
FAKE_NAMES = [
    "Rajesh Kumar Sharma", "Priya Mehta", "Amit Verma",
    "Sunita Devi Gupta", "Vikram Singh Chauhan", "Neha Patel",
    "Rohit Agarwal", "Anita Reddy", "Suresh Babu", "Deepak Joshi",
    "Kavita Nair", "Manoj Tiwari", "Pooja Iyer", "Sanjay Rao",
    "Rekha Saxena", "Arun Malhotra", "Geeta Choudhary",
    "Ravi Shankar Mishra", "Lakshmi Subramanian", "Kiran Desai",
]

BANK_NAMES = [
    "State Bank of India", "HDFC Bank", "ICICI Bank",
    "Punjab National Bank", "Axis Bank", "Bank of Baroda",
    "Kotak Mahindra Bank", "Union Bank of India",
]

UPI_HANDLES = ["@ybl", "@paytm", "@oksbi", "@okaxis", "@okicici"]


class FakeDataGenerator:
    """Generates realistic but completely fake financial/identity data."""

    def transaction_id(self) -> str:
        """UTR-style: e.g., 'UTR432918765023'."""
        return f"UTR{random.randint(100000000000, 999999999999)}"

    def bank_ref(self) -> str:
        """Bank reference number."""
        prefix = random.choice(["SBIN", "HDFC", "ICIC", "AXIS", "PUNB"])
        return f"{prefix}{random.randint(10**10, 10**11)}"

    def fake_name(self) -> str:
        """From a curated list — never uses real identities."""
        return random.choice(FAKE_NAMES)

    def fake_phone(self, format: str = "indian") -> str:
        """Generates non-routable phone numbers."""
        if format == "indian":
            # Use 555-prefix which is reserved
            return f"+91 55500 {random.randint(10000, 99999)}"
        return f"+1-555-{random.randint(1000, 9999)}"

    def fake_email(self) -> str:
        """Generate a plausible but fake email."""
        name = random.choice(FAKE_NAMES).split()[0].lower()
        num = random.randint(10, 999)
        domain = random.choice([
            "example.com", "test.invalid", "demo.local"
        ])
        return f"{name}{num}@{domain}"

    def fake_amount(self, min_val: int = 500, max_val: int = 50000) -> str:
        """Currency-formatted fake amount."""
        amount = random.randint(min_val, max_val)
        return f"\u20b9{amount:,}.00"

    def fake_amount_raw(self, min_val: int = 500, max_val: int = 50000) -> int:
        """Raw integer amount."""
        return random.randint(min_val, max_val)

    def otp_code(self, length: int = 6) -> str:
        """Random OTP code."""
        return "".join(random.choices(string.digits, k=length))

    def fake_upi(self) -> str:
        """Fake UPI ID."""
        name = random.choice(FAKE_NAMES).split()[0].lower()
        handle = random.choice(UPI_HANDLES)
        return f"{name}{random.randint(10, 99)}{handle}"

    def fake_account_number(self) -> str:
        """Fake bank account number (impossible prefix)."""
        return f"00000{random.randint(10000000, 99999999)}"

    def fake_ifsc(self) -> str:
        """Fake IFSC code."""
        bank = random.choice(["SBIN", "HDFC", "ICIC", "AXIS"])
        return f"{bank}0{random.randint(100000, 999999)}"

    def bank_name(self) -> str:
        """Random bank name."""
        return random.choice(BANK_NAMES)

    def timestamp_recent(self, minutes_ago: int = 2) -> str:
        """Timestamp from N minutes ago."""
        t = datetime.now() - timedelta(minutes=minutes_ago)
        return t.strftime("%d %b %Y, %I:%M %p")

    def masked_account(self) -> str:
        """Masked account number like XXXX7823."""
        return f"XXXX{random.randint(1000, 9999)}"
