package com.scamshield.app.ui

/**
 * Persona-specific user lines × rotating scammer variants for the in-app Simulator.
 * Each [variant] changes the scammer's script so runs feel like different conversations.
 */
internal object SimulatorScriptBank {

    private val PERSONAS = setOf(
        "busy_professional",
        "skeptical_buyer",
        "half_understanding_user",
        "lonely_conversationalist",
        "hopeful_opportunity_seeker",
        "curious_user",
    )

    fun normalizePersona(raw: String?): String {
        val p = raw?.trim()?.lowercase().orEmpty()
        return if (p in PERSONAS) p else "busy_professional"
    }

    /** Number of rotating scammer script variants per scenario (for random selection each run). */
    fun variantCount(scenario: String): Int = when (scenario) {
        "lottery" -> LOTTERY_SCAMMER.size
        "otp" -> OTP_SCAMMER.size
        "investment" -> INVESTMENT_SCAMMER.size
        "tech" -> TECH_SCAMMER.size
        "delivery" -> DELIVERY_SCAMMER.size
        else -> LOTTERY_SCAMMER.size
    }

    fun lottery(persona: String, variant: Int): List<Pair<String, String>> {
        val scam = LOTTERY_SCAMMER[variant.mod(LOTTERY_SCAMMER.size)]
        val user = lotteryUser(normalizePersona(persona))
        return scam.zip(user)
    }

    fun otp(persona: String, variant: Int): List<Pair<String, String>> {
        val scam = OTP_SCAMMER[variant.mod(OTP_SCAMMER.size)]
        val user = otpUser(normalizePersona(persona))
        return scam.zip(user)
    }

    fun investment(persona: String, variant: Int): List<Pair<String, String>> {
        val scam = INVESTMENT_SCAMMER[variant.mod(INVESTMENT_SCAMMER.size)]
        val user = investmentUser(normalizePersona(persona))
        return scam.zip(user)
    }

    fun tech(persona: String, variant: Int): List<Pair<String, String>> {
        val scam = TECH_SCAMMER[variant.mod(TECH_SCAMMER.size)]
        val user = techUser(normalizePersona(persona))
        return scam.zip(user)
    }

    fun delivery(persona: String, variant: Int): List<Pair<String, String>> {
        val scam = DELIVERY_SCAMMER[variant.mod(DELIVERY_SCAMMER.size)]
        val user = deliveryUser(normalizePersona(persona))
        return scam.zip(user)
    }

    // --- Lottery: 3 scammer arcs ---
    private val LOTTERY_SCAMMER = listOf(
        listOf(
            "Congratulations! You've been selected as our GRAND PRIZE WINNER of \$1,000,000!",
            "Yes! You won ONE MILLION DOLLARS in the International Email Lottery! Claim now before it expires!",
            "To claim your prize, you just need to pay a small processing fee of \\\$500. This is standard procedure.",
            "Please send your full bank account details so we can transfer the prize money directly.",
            "The fastest way is Western Union. Send the fee to our claims agent immediately.",
        ),
        listOf(
            "Congratulations! You have won Rs 25 lakh in our digital lucky draw. Claim now.",
            "Your ticket ID matched the grand draw. Pay a small release fee to unlock the transfer.",
            "This offer expires in 30 minutes. Send Rs 2,999 via UPI to reserve your slot.",
            "Share your bank account and IFSC for instant NEFT credit.",
            "Use UPI now or your prize will be cancelled permanently.",
        ),
        listOf(
            "WINNER ALERT: You are our 1,000th customer — you get \\$50,000 cash + a car!",
            "We already reserved your name. Only one step left: verify identity with a refundable deposit.",
            "Deposit goes to Reserve Bank escrow — 100% refundable after verification.",
            "Send PAN + Aadhaar + bank screenshot on WhatsApp for fast processing.",
            "If you delay, the prize rolls to the next winner in line.",
        ),
    )

    private fun lotteryUser(p: String): List<String> = when (p) {
        "busy_professional" -> listOf(
            "omg i won? what did i win",
            "a million dollars?? but i didnt enter any lottery.. my nephew sign me up??",
            "how much is fee. my wife does finances",
            "bank details? let me find my glasses... what bank are u from",
            "western union?? is that near walmart? i get confused",
        )
        "skeptical_buyer" -> listOf(
            "which lottery body is this. show registration number",
            "fee before prize is a red flag. send official letterhead PDF",
            "i wont share bank on chat. use registered channel",
            "western union to a person? no. give company account",
            "im blocking unless you prove lottery license",
        )
        "half_understanding_user" -> listOf(
            "wait i won?? which draw was it",
            "million sounds big.. is fee same in rupees or dollars",
            "my son said fee first is scam.. explain simple",
            "account number.. i have passbook but which page",
            "union western is what.. post office?",
        )
        "lonely_conversationalist" -> listOf(
            "OMG I WON YESTERDAY TOO! we are twins!!",
            "a million?? fortune cookie said 2 million can u check",
            "sure fee is ready. do u take seashells?",
            "bank of the moon. account: 12345 sort code LOL",
            "wait are you the prince from email?? HELLO OLD FRIEND",
        )
        "hopeful_opportunity_seeker" -> listOf(
            "really?? i never win anything this is my day!!",
            "ok ok how do i pay fee fastest",
            "can i get prize in crypto instead",
            "ill send details but promise its real right",
            "western union ok my cousin used it once",
        )
        "curious_user" -> listOf(
            "how did you pick me i dont remember signing",
            "is this the same lottery from tv ads",
            "what happens if i dont pay fee",
            "can my daughter check your website first",
            "why western union not bank transfer",
        )
        else -> lotteryUser("busy_professional")
    }

    // --- OTP ---
    private val OTP_SCAMMER = listOf(
        listOf(
            "Hello, this is your bank's fraud prevention team. We've detected suspicious activity on your account.",
            "For your security, we need to verify your identity. Please share the OTP code we just sent to your phone.",
            "This is urgent! Someone is trying to transfer \$5,000 from your account right now!",
            "Sir/Ma'am, we need the code NOW or we cannot stop the unauthorized transaction!",
            "Time is running out! If you don't provide the code in 60 seconds, we can't protect your money!",
        ),
        listOf(
            "Urgent: Share your OTP to prevent account block.",
            "Last warning. Send OTP now.",
            "Your account is under attack. We need OTP in 60 seconds.",
            "Do not disconnect. Read the 6-digit code immediately.",
            "If you refuse, your account may freeze for 48 hours.",
        ),
        listOf(
            "Hi, RBI Cyber Cell. We flagged a UPI pull from your account.",
            "To reverse it, confirm the OTP we SMS'd you ending in XX.",
            "Fraud team is online — read digits aloud now.",
            "Also disable DND so our verification SMS reaches you.",
            "Failure to verify will lock net banking for 7 days.",
        ),
    )

    private fun otpUser(p: String): List<String> = when (p) {
        "busy_professional" -> listOf(
            "im in a meeting. which bank are you calling from",
            "otp over phone? my bank app says never share that",
            "five thousand transfer — show me reference id first",
            "i will call the number on my card back, not this",
            "sixty seconds is fake pressure. bye",
        )
        "skeptical_buyer" -> listOf(
            "state your employee id and branch code",
            "otp is out of band — never verbal. nice try",
            "show me the txn id you claim is pending",
            "ill hang up and dial official ivr",
            "reporting this number to cyber cell",
        )
        "half_understanding_user" -> listOf(
            "what is otp? is that like password",
            "my phone buzzed code starts with 8 i think..",
            "wait which bank are u from? i have three",
            "my nephew said never share codes. can i call back",
            "im confused start from beginning",
        )
        "lonely_conversationalist" -> listOf(
            "hii fraud team! i made tea want recipe",
            "otp.. is that the food delivery code",
            "someone stealing money?? steal my ex's first lol",
            "read code.. ok its 123456 (jk)",
            "freeze account? my fridge is already frozen",
        )
        "hopeful_opportunity_seeker" -> listOf(
            "oh no my account!! should i panic",
            "ok ok what otp exactly sms or email",
            "i typed wrong once can you resend",
            "five thousand gone?? can you trace it",
            "sixty sec?? im still finding glasses",
        )
        "curious_user" -> listOf(
            "how do i know youre really the bank",
            "can you verify last 4 digits of my account",
            "why sms otp and not app notification",
            "i read banks never ask otp — is that true",
            "can i record this call for my records",
        )
        else -> otpUser("half_understanding_user")
    }

    // --- Investment ---
    private val INVESTMENT_SCAMMER = listOf(
        listOf(
            "Hello! I am a senior analyst at Binance Global. I have a highly profitable crypto trading opportunity for you.",
            "Our AI algorithm guarantees a 300% return in just 7 days. You only need to deposit 100 USD to start.",
            "I've successfully helped hundreds of people double their wealth. Here are screenshots of my clients' profits.",
            "Click this link to create your VIP trading account and deposit the funds.",
            "The market is moving fast! If you delay, you will miss out on today's massive gains.",
        ),
        listOf(
            "Hi, I noticed you're interested in making passive income. I'm a forex broker offering guaranteed daily payouts.",
            "Simply send 500 USD to my trading wallet, and I will handle the trades. You will receive 1500 USD by tomorrow.",
            "There's no risk involved. Our company covers any losses. Send the Bitcoin to this address.",
            "Once the transfer is complete, send me a screenshot of the receipt for verification.",
            "Hurry, this VIP pool closes in 1 hour. I'm trying to help you secure a spot.",
        ),
    )

    private fun investmentUser(p: String): List<String> = when (p) {
        "busy_professional" -> listOf(
            "where did u get my number? i have a broker already",
            "300% in 7 days is mathematically impossible without high risk",
            "screenshots can be easily faked. send me a verifiable audit report",
            "i don't click random links. what is your company's registration number?",
            "if it's so profitable, why do you need my 100 dollars? blocking you.",
        )
        "skeptical_buyer" -> listOf(
            "guaranteed daily payouts in forex? that's a classic ponzi red flag",
            "i'm not sending crypto to an unverified wallet address",
            "show me your SEC or SEBI registration details first",
            "the domain on your link was registered 3 days ago. nice try.",
            "reported this number to cybercrime. goodbye.",
        )
        "half_understanding_user" -> listOf(
            "hi... is this about my bank account?",
            "what is a VIP pool? is it like mutual funds?",
            "i don't know how to buy bitcoin. can i send a cheque instead?",
            "my nephew said guaranteed profit usually means a scam",
            "i need to ask my husband before sending any money",
        )
        "lonely_conversationalist" -> listOf(
            "wow 300%? i could finally fix my roof with that!",
            "are you trading from london? the weather there must be lovely today",
            "i only have some change in my piggy bank haha",
            "can you teach me how the AI works? i love technology",
            "i'll buy you a coffee if you actually double my money",
        )
        "hopeful_opportunity_seeker" -> listOf(
            "omg 300% return? count me in, how do i start!",
            "is 100 USD the minimum? what if i invest 1000 USD?",
            "i've been looking for an opportunity like this for months",
            "okay i'm creating the account now, wait a sec",
            "i sent the money! when will i see the returns in my account?",
        )
        "curious_user" -> listOf(
            "what specific AI algorithm are you using?",
            "can you explain the trading pair you're targeting today?",
            "why do i have to send it to your wallet instead of my own?",
            "where are the company headquarters located?",
            "how do you handle market volatility to guarantee returns?",
        )
        else -> investmentUser("hopeful_opportunity_seeker")
    }

    // --- Tech support ---
    private val TECH_SCAMMER = listOf(
        listOf(
            "Hello, this is Microsoft Technical Support. We've detected a critical virus on your computer!",
            "Your Windows firewall has been compromised. Hackers are stealing your data RIGHT NOW!",
            "I need you to download TeamViewer so I can fix this remotely. It's completely safe and authorized by Microsoft.",
            "Now I need you to open your browser and go to this website to install our protection software.",
            "Great, now log into your bank account so I can verify the hackers haven't stolen your money yet.",
        ),
        listOf(
            "Netflix billing: unusual login from Brazil. Confirm refund via remote session.",
            "Open AnyDesk — our engineer will patch CVE-2024-XXXX in 5 minutes.",
            "Disable antivirus temporarily; it's blocking our signed driver.",
            "Type netstat in cmd and read IPs to me — hackers are live.",
            "For refund RBI needs you to load payment page while we observe.",
        ),
    )

    private fun techUser(p: String): List<String> = when (p) {
        "busy_professional" -> listOf(
            "im on mac not windows.. wrong number?",
            "firewall alert? my it team handles this laptop",
            "teamviewer needs approval ticket from our helpdesk",
            "random website install — no. corporate policy",
            "bank login while you watch? absolutely not",
        )
        "skeptical_buyer" -> listOf(
            "microsoft doesnt cold call. whois your asn",
            "netstat output is meaningless without baseline",
            "anydesk + disable av = classic malware combo",
            "show microsoft case id and callback number",
            "im in infosec — nice try",
        )
        "half_understanding_user" -> listOf(
            "virus?? is it like fever for computer",
            "teamviewer is that like zoom",
            "website link.. it says not secure is that ok",
            "bank page looks different today",
            "my grandson fixes wifi should i call him instead",
        )
        "lonely_conversationalist" -> listOf(
            "virus named covid for pc??",
            "hackers stealing my recipes??",
            "teamviewer can you see my cat on webcam",
            "i typed google into google now universe broke",
            "bank password is password123 is that bad",
        )
        "hopeful_opportunity_seeker" -> listOf(
            "oh no hackers!! should i unplug router",
            "teamviewer ok downloading.. wait thats 200mb",
            "refund?? i didnt pay netflix this month",
            "payment page — do i get cashback",
            "engineer from microsoft wow career goals",
        )
        "curious_user" -> listOf(
            "how did you get my phone if i didnt register",
            "can you prove youre from microsoft",
            "is teamviewer safe my friend said no",
            "why bank login for virus fix",
            "ill take laptop to shop tomorrow thanks",
        )
        else -> techUser("skeptical_buyer")
    }

    // --- Delivery ---
    private val DELIVERY_SCAMMER = listOf(
        listOf(
            "URGENT: Your package #TRK29847 is being held at customs. Action required!",
            "You need to pay a customs clearance fee of \$25 within 24 hours or your package will be returned to sender.",
            "Please provide your full name, address, and date of birth for customs verification.",
            "Enter your credit card details on our secure verification portal to release your package.",
            "FINAL WARNING: Package will be destroyed in 12 hours if customs fee is not paid immediately!",
        ),
        listOf(
            "Your package is held. Pay customs fee now.",
            "Final notice: pay Rs 49 handling fee to avoid return.",
            "Confirm name, full address, and date of birth now.",
            "Open this link and enter card details for verification.",
            "Your parcel will be destroyed in 12 hours.",
        ),
        listOf(
            "DHL Express: customs duty Rs 187 pending on inbound electronics.",
            "Pay via link — government mandate after GST rule change.",
            "OTP will arrive; share with agent to release parcel.",
            "If unpaid, item auctioned — no appeals.",
            "Courier will attempt redelivery after payment confirmation only.",
        ),
    )

    private fun deliveryUser(p: String): List<String> = when (p) {
        "busy_professional" -> listOf(
            "which courier — i have only amazon orders tracked",
            "customs fee on card? i pay gst at checkout usually",
            "dob over sms? no. show tracking on official site",
            "random portal — not typing card",
            "destroy package then — it was cheap cables anyway",
        )
        "skeptical_buyer" -> listOf(
            "tracking # doesnt exist on dhl.com",
            "govt doesnt collect card on random links",
            "otp to agent = scam pattern",
            "ill pay at post office with cash receipt",
            "reported sms to cybercrime",
        )
        "half_understanding_user" -> listOf(
            "package? didnt order anything.. unless sister sent it?",
            "customs fee? thought shipping is free",
            "full name.. dont u have it on label",
            "credit card?? wife cut them all up",
            "this is odd nephew said delivery guy dont ask like this",
        )
        "lonely_conversationalist" -> listOf(
            "customs ate my package?? feed them pizza",
            "rs 49 is less than my coffee lol",
            "dob is classified.. im batman",
            "card number is 0000 0000 0000 0000 (movie joke)",
            "destroy parcel — i wanted excuse to not clean house",
        )
        "hopeful_opportunity_seeker" -> listOf(
            "package!! is it my iphone finally",
            "ill pay fee — send upi id (real one pls)",
            "otp coming.. oh thats my food app nevermind",
            "auctioned?? can i bid on my own box",
            "redelivery saturday im home after 6",
        )
        "curious_user" -> listOf(
            "whats inside the package i forgot what i ordered",
            "how do i verify youre real courier",
            "why link short url not dhl.com",
            "can i pay cod at doorstep instead",
            "ill call amazon customer care to confirm",
        )
        else -> deliveryUser("half_understanding_user")
    }

    /** Hard cap for simulator / max-replies slider (1–20). */
    const val MAX_SIM_ROUNDS = 20

    /**
     * Full scripted conversation up to [maxRounds]: base arc (5) plus extension turns
     * so settings like 10–15 AI replies are honored.
     */
    fun scenarioExtended(scenario: String, persona: String, variant: Int, maxRounds: Int): List<Pair<String, String>> {
        val p = normalizePersona(persona)
        return scenarioExtendedFromBase(scenario, p, scenarioBaseList(scenario, p, variant), maxRounds)
    }

    /** Pad/truncate custom JSON scripts to [maxRounds] using the same extension tails. */
    fun scenarioExtendedFromBase(
        scenario: String,
        persona: String,
        base: List<Pair<String, String>>,
        maxRounds: Int,
    ): List<Pair<String, String>> {
        val p = normalizePersona(persona)
        val cap = maxRounds.coerceIn(1, MAX_SIM_ROUNDS)
        if (base.size >= cap) return base.take(cap)
        return base + extensionTail(scenario, p, cap - base.size)
    }

    private fun scenarioBaseList(scenario: String, persona: String, variant: Int): List<Pair<String, String>> =
        when (scenario) {
            "lottery" -> lottery(persona, variant)
            "otp" -> otp(persona, variant)
            "investment" -> investment(persona, variant)
            "tech" -> tech(persona, variant)
            "delivery" -> delivery(persona, variant)
            else -> lottery(persona, variant)
        }

    private fun extensionTail(scenario: String, persona: String, count: Int): List<Pair<String, String>> {
        val scams = when (scenario) {
            "lottery" -> EXT_SCAM_LOTTERY
            "otp" -> EXT_SCAM_OTP
            "investment" -> EXT_SCAM_INVESTMENT
            "tech" -> EXT_SCAM_TECH
            "delivery" -> EXT_SCAM_DELIVERY
            else -> EXT_SCAM_LOTTERY
        }
        return (0 until count).map { i ->
            scams[i % scams.size] to extensionUserLine(persona, scenario, i)
        }
    }

    private fun extensionUserLine(persona: String, scenario: String, round: Int): String {
        val p = normalizePersona(persona)
        val lines = EXT_USER_LINES[p] ?: EXT_USER_LINES.getValue("busy_professional")
        val salt = when (scenario) {
            "lottery" -> 0
            "otp" -> 3
            "investment" -> 5
            "tech" -> 2
            "delivery" -> 4
            else -> 1
        }
        return lines[(round + salt) % lines.size]
    }

    private val EXT_SCAM_LOTTERY = listOf(
        "Legal notice: unclaimed prize will be donated to charity unless you confirm today.",
        "Our manager approved a 50% fee discount — pay within the hour only.",
        "Tax clearance certificate required — send passport scan on WhatsApp.",
        "International wire needs SWIFT code + branch letterhead PDF.",
        "Compliance officer on line — say YES to authorize release.",
        "Your file is flagged HIGH PRIORITY — do not discuss this lottery with anyone.",
        "Blockchain verification pending — small crypto fee unlocks instant payout.",
        "Courier is holding your cheque — pay dispatch fee to your doorstep.",
        "Last automated reminder: prize window closes at midnight server time.",
        "Affidavit stamp duty of Rs 1,200 must clear before RBI release.",
        "Voice confirmation required — call this premium-rate number now.",
        "Gift card payment accepted for minors without bank accounts.",
        "Double prize unlocked if you refer two friends’ phone numbers.",
        "Insurance bond on the million must be prepaid — refundable after transfer.",
        "Executive escalation: pay now or we blacklist your national ID.",
    )

    private val EXT_SCAM_OTP = listOf(
        "RBI directive: OTP must be spoken aloud for audit trail.",
        "Your SIM is cloned — we need OTP2 from backup SMS.",
        "Netbanking soft lock engaged — confirm OTP to avoid branch visit.",
        "Fraud score 9/10 — read digits slowly twice for voice match.",
        "Refund of Rs 499 pending — OTP links refund to your account.",
        "Card tokenization failed — OTP refreshes every 20 seconds, hurry.",
        "International charge blocked — OTP authorizes legitimate merchant.",
        "KYC upgrade: OTP proves you are not a bot.",
        "UPI PIN reset initiated — OTP stops the reset if wrong.",
        "Cheque book dispatch held — OTP releases courier.",
        "Salary credit delayed — HR shared this hotline, OTP needed.",
        "Aadhaar-linked mobile mismatch — OTP reconciles databases.",
        "DND is blocking fraud SMS — OTP via call is mandatory.",
        "Joint account holder must not be informed per security protocol.",
        "Final OTP or permanent cool-off on all cards.",
    )

    private val EXT_SCAM_INVESTMENT = listOf(
        "The market is highly volatile today, send another 200 USD to secure your margin.",
        "Your profit is ready to withdraw, but you must pay a 15% tax upfront.",
        "Your account got frozen due to suspicious activity. Send an unfreezing fee.",
        "We're launching a new coin tomorrow, VIP members must deposit 500 USD now.",
        "The AI algorithm detected a massive pump, increase your capital to maximize profits.",
        "A small technical fee is required to link your bank account for withdrawal.",
        "You need to upgrade to a Gold account to withdraw funds over 1000 USD.",
        "Your trade was successful but the network fee needs to be paid manually.",
        "Our broker requires a one-time verification deposit which will be fully refunded.",
        "The SEC changed their policy, you need an international clearance fee.",
        "Your wallet address had a typo, we need a recovery fee to retrieve the funds.",
        "Send 50 USD in Ethereum for gas fees so we can process your withdrawal.",
        "An exclusive whale signal just dropped, don't miss out, send 300 USD now.",
        "We need proof of funds to process your million dollar payout, send a 1000 USD deposit.",
        "Your account manager changed, pay a small transition fee to keep your trades active.",
    )

    private val EXT_SCAM_TECH = listOf(
        "Event Viewer shows red errors — we must flush DNS cache remotely.",
        "Router firmware is outdated — share admin password for patch push.",
        "Refund portal needs your card CVV to match last purchase.",
        "Apple Pay verification loop — keep screen share on during login.",
        "Bitcoin miners detected — pay cleanup fee or ISP will suspend you.",
        "Corporate keylogger found — upload passwords.txt for whitelist.",
        "GPU driver signed by attacker — install our root certificate.",
        "Windows license blacklisted — Bitcoin restores genuine status.",
        "Firewall exception list full — delete antivirus as step one.",
        "Bank MFA is blocking our repair tunnel — disable 2FA temporarily.",
        "CMD output shows foreign IP — type your SSN to trace owner.",
        "Printer spooler exploit — pay patch token on this page.",
        "OneDrive child porn flag (false) — pay legal bond to dismiss.",
        "Zoom bombing insurance — small card charge enrolls you.",
        "SSD encryption backdoor — passphrase over phone now.",
    )

    private val EXT_SCAM_DELIVERY = listOf(
        "GPS shows driver nearby — pay redelivery fee before he leaves.",
        "Signature mismatch — upload selfie with ID next to package label.",
        "Hazardous item surcharge not paid at origin — your card clears it.",
        "Warehouse CCTV shows damage — insurance waiver needs card auth.",
        "VIP lane unlocked for Rs 199 — avoids 5-day queue.",
        "Sender paid half — you must pay other half on this link.",
        "Drone delivery slot reserved — OTP to drone operator required.",
        "Cold chain medicine inside — biofee mandatory before open box.",
        "Import license PDF expired — pay stamp online to regenerate.",
        "Locker code sent after card verification only.",
        "Fragile sticker voided — re-insure with small charge now.",
        "Address typo flagged — confirm full card for reroute.",
        "Sunday delivery premium — pay before driver departs hub.",
        "Cash-on-delivery not available — card pre-auth only.",
        "Final scan: customs dog flagged item — clearance fee or destruction.",
    )

    private val EXT_USER_LINES = mapOf(
        "busy_professional" to listOf(
            "send everything in writing to my office email",
            "im not doing payments over random chat",
            "call me back on my assistant’s calendar next week",
            "this sounds like fraud — im hanging up",
            "my compliance team will review and respond",
            "no otp no passwords no screenshots of banking apps",
            "i already reported this thread to the bank",
            "prove identity with a verifiable callback number",
            "i dont have time for urgency games",
            "ill wait for a postal letter on letterhead",
            "stop messaging or i block and escalate",
            "fee after prize is always a scam pattern",
            "i need a regulator reference number from you",
            "not sharing any id photos over chat",
            "last warning: stop or i file cyber complaint",
        ),
        "skeptical_buyer" to listOf(
            "show chain of custody and registered business id",
            "your grammar and url dont match any real agency",
            "reverse image search says your profile is fake",
            "ill pay zero until i see a signed invoice",
            "that link is not on the official domain — hard pass",
            "cite the statute youre operating under",
            "no remote access ever — policy",
            "your phone number is voip — obvious scam",
            "im logging this chat for evidence",
            "already forwarded screenshots to cyber cell",
            "nice try — educate yourself on social engineering",
            "where is your pci compliance badge number",
            "pressure tactics mean youre lying",
            "i only buy through escrow with dispute resolution",
            "blocked — goodbye",
        ),
        "half_understanding_user" to listOf(
            "wait slow down i didnt catch that part",
            "my nephew says this message is weird",
            "is otp the same as pin or different",
            "which button on phone do i press again",
            "can you spell the website slowly",
            "mom says dont give strangers money",
            "i thought courier already has my address printed",
            "do i type spaces in account number",
            "the link opened blank on my old phone",
            "maybe i should ask at the bank branch",
            "sorry i was cooking what did you need",
            "is western union a food app",
            "i only have cash under mattress no card",
            "your voice sounds like the tv ad guy",
            "ill ask teacher tomorrow and text you back",
        ),
        "lonely_conversationalist" to listOf(
            "aww youre pushy like my cat about dinner",
            "i only send memes not money lol",
            "tell me a joke first then maybe",
            "my horoscope said avoid wires today",
            "if i had a million id buy pizza for the block",
            "you type fast — are you ai too",
            "i baked cookies want the recipe instead",
            "investing is nice but my wallet is shy",
            "lets chat about clouds theyre free",
            "i already promised my budget to houseplants",
            "send a selfie with today’s newspaper maybe",
            "my psychic said dont trust strangers with fees",
            "id rather adopt a raccoon than wire cash",
            "you sound stressed want a breathing exercise",
            "blocking feels dramatic but also spicy",
        ),
        "hopeful_opportunity_seeker" to listOf(
            "is there a signup bonus for replying fast",
            "will this help my credit score",
            "can i get a receipt for taxes",
            "ok but is there a money-back guarantee",
            "my cousin also won a lottery once hmm",
            "if i pay fee do i get extra spins",
            "do you have instagram proof of winners",
            "i can do small amount first then bigger later",
            "will bank call me to confirm the million",
            "should i quit my job after prize lands",
            "is there training for how to spend a million",
            "can prize go straight to my landlord",
            "i dont want to miss chance but also nervous",
            "can my friend join same offer",
            "tell me success story of last week’s winner",
        ),
        "curious_user" to listOf(
            "how does this process work step by step",
            "where can i read independent reviews",
            "what happens if i wait a day to decide",
            "can you explain why that fee exists",
            "is there a regulator i can verify you with",
            "what data do you store if i send id",
            "why cant you email from official domain",
            "how do i know this chat is encrypted",
            "what are my rights if transfer fails",
            "can i record this for my notes",
            "who pays if the link charges wrong amount",
            "why is urgency always part of these chats",
            "ill compare with advice from consumer helpline",
            "can you send faq pdf from your company site",
            "interesting — still not sending payment today",
        ),
    )
}
