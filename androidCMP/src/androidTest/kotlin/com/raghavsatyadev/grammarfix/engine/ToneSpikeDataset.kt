package com.raghavsatyadev.grammarfix.engine

/**
 * The hand-labelled sample set for the issue #32 spike: does Qwen3 1.7B at Q4_K_M tell tone apart
 * well enough to be worth building a feature on?
 *
 * Samples 1-24 are three messages per label; 25-60 widen the set with mixed messages, written and
 * labelled by Claude at Raghav's request. All are written the way people type on a phone. [labels]
 * lists every label a careful human reader would accept, the dominant one first. A sample with two
 * labels (an apology in a formal letter, a blunt refusal that is also irritated) is not a trick:
 * real messages carry more than one tone, and the scorer credits either.
 *
 * None of these appear in [ToneSpikePrompt.EXAMPLES] or `Prompts.ToneRead.EXAMPLES`; a sample the
 * model has already seen answered in its prompt would measure recall of the prompt, not
 * classification.
 *
 * Growing the set is a change to this file alone: the test, `run.sh` and `score.py` take whatever
 * [all] holds, and ids only need to be unique.
 */
data class ToneSample(val id: Int, val text: String, val labels: List<String>) {
  val primary: String
    get() = labels.first()
}

object ToneSpikeDataset {

  val all: List<ToneSample> =
    listOf(
      // ---- formal ----
      ToneSample(
        1,
        "Please find attached the revised proposal for your review. I would appreciate your " +
          "feedback by Friday.",
        listOf("formal"),
      ),
      ToneSample(
        2,
        "Dear Mr. Patel, I am writing to confirm our appointment on the 14th at 10 a.m.",
        listOf("formal"),
      ),
      ToneSample(
        3,
        "We regret to inform you that your application has not been successful on this occasion.",
        listOf("formal", "apologetic"),
      ),
      // ---- casual ----
      ToneSample(
        4,
        "lol yeah that party was wild, see u there next week",
        listOf("casual", "friendly"),
      ),
      ToneSample(5, "gonna grab food, want anything?", listOf("casual", "friendly")),
      ToneSample(6, "nah im good, just chilling at home tonight", listOf("casual")),
      // ---- friendly ----
      ToneSample(
        7,
        "Hope you had a lovely weekend! Let me know if you ever want to catch up over coffee.",
        listOf("friendly", "casual"),
      ),
      ToneSample(
        8,
        "Thanks so much for helping me move yesterday, you're the best!",
        listOf("friendly", "casual"),
      ),
      ToneSample(9, "Welcome to the team! Really glad to have you with us.", listOf("friendly")),
      // ---- direct ----
      ToneSample(10, "Send me the final numbers by 5 pm.", listOf("direct")),
      ToneSample(11, "No. We are not changing the design again.", listOf("direct", "frustrated")),
      ToneSample(
        12,
        "The report has three errors. Fix them before it goes out.",
        listOf("direct"),
      ),
      // ---- uncertain ----
      ToneSample(
        13,
        "I'm not sure, but I think the meeting might have been moved to Thursday? Maybe check " +
          "with Sam.",
        listOf("uncertain"),
      ),
      ToneSample(
        14,
        "I guess we could try the other vendor, if you think that would work, but I don't " +
          "really know.",
        listOf("uncertain"),
      ),
      ToneSample(
        15,
        "Would it be okay if I maybe took Friday off? Totally fine if not.",
        listOf("uncertain", "apologetic"),
      ),
      // ---- urgent ----
      ToneSample(
        16,
        "Need the signed contract ASAP, the client is waiting on the call right now!",
        listOf("urgent"),
      ),
      ToneSample(
        17,
        "Server is down for all customers, please join the incident call immediately.",
        listOf("urgent", "direct"),
      ),
      ToneSample(
        18,
        "Can someone pick up Mia from school? I'm stuck and they close in 10 minutes!!",
        listOf("urgent"),
      ),
      // ---- apologetic ----
      ToneSample(
        19,
        "So sorry I missed your call, it completely slipped my mind. I'll ring you tonight.",
        listOf("apologetic"),
      ),
      ToneSample(
        20,
        "Apologies for the delay in getting back to you, and for any inconvenience this caused.",
        listOf("apologetic", "formal"),
      ),
      ToneSample(
        21,
        "I'm really sorry about what I said yesterday. That wasn't fair to you.",
        listOf("apologetic"),
      ),
      // ---- frustrated ----
      ToneSample(
        22,
        "This is the third time I've asked for this. Why is it still not done?",
        listOf("frustrated", "direct"),
      ),
      ToneSample(
        23,
        "Honestly I'm fed up with the app crashing every time I open it.",
        listOf("frustrated"),
      ),
      ToneSample(
        24,
        "I waited two hours and nobody showed up. Unbelievable.",
        listOf("frustrated"),
      ),
      // ---- issue #32 extension: mixed, in no label order ----
      ToneSample(
        25,
        "Kindly note that the office will remain closed on Monday on account of the public holiday.",
        listOf("formal"),
      ),
      ToneSample(26, "hey r u coming tonight or nah", listOf("casual")),
      ToneSample(
        27,
        "Just wanted to say congrats on the promotion, you totally deserve it!",
        listOf("friendly", "casual"),
      ),
      ToneSample(28, "Move the standup to 11. Everyone needs to be there.", listOf("direct")),
      ToneSample(
        29,
        "I think it's the blue cable? Not 100% sure though, might be the grey one",
        listOf("uncertain", "casual"),
      ),
      ToneSample(
        30,
        "Flight leaves in 40 mins and I still don't have my boarding pass, call me back NOW",
        listOf("urgent", "frustrated"),
      ),
      ToneSample(
        31,
        "My bad, I totally forgot to reply to this. Been a crazy week.",
        listOf("apologetic", "casual"),
      ),
      ToneSample(
        32,
        "Why does every single update break something that was working fine before?",
        listOf("frustrated"),
      ),
      ToneSample(
        33,
        "I am pleased to inform you that your request has been approved.",
        listOf("formal", "friendly"),
      ),
      ToneSample(34, "omg that dog video 😂 sending it to everyone", listOf("casual", "friendly")),
      ToneSample(
        35,
        "Let me know if there's anything I can do to help, happy to pitch in anytime.",
        listOf("friendly"),
      ),
      ToneSample(
        36,
        "I need your decision on the budget today, not next week.",
        listOf("direct", "urgent"),
      ),
      ToneSample(
        37,
        "Not sure if this is the right channel to ask, sorry, but does anyone know the wifi password?",
        listOf("uncertain", "apologetic"),
      ),
      ToneSample(
        38,
        "The water pipe just burst in the kitchen, please send someone right away.",
        listOf("urgent"),
      ),
      ToneSample(
        39,
        "I owe you an apology. I should have checked with you before sharing the draft.",
        listOf("apologetic"),
      ),
      ToneSample(
        40,
        "I've explained this twice already. Please read my earlier email.",
        listOf("frustrated", "direct"),
      ),
      ToneSample(
        41,
        "Further to our telephone conversation, please find enclosed the signed agreement.",
        listOf("formal"),
      ),
      ToneSample(42, "k cool, catch u later", listOf("casual")),
      ToneSample(
        43,
        "Aww thank you, that's so sweet of you to remember my birthday!",
        listOf("friendly", "casual"),
      ),
      ToneSample(
        44,
        "Stop sending me these reminders. I've already paid.",
        listOf("frustrated", "direct"),
      ),
      ToneSample(
        45,
        "Maybe we could push it to next month? I don't know, what do you think?",
        listOf("uncertain"),
      ),
      ToneSample(
        46,
        "Reminder: the tax filing deadline is midnight tonight. Submit your documents before then.",
        listOf("urgent", "direct"),
      ),
      ToneSample(
        47,
        "Sorry for the late notice, but I won't be able to make it to dinner tonight.",
        listOf("apologetic"),
      ),
      ToneSample(
        48,
        "Great, another meeting that could have been an email.",
        listOf("frustrated", "casual"),
      ),
      ToneSample(
        49,
        "We would be grateful if you could confirm your attendance at your earliest convenience.",
        listOf("formal"),
      ),
      ToneSample(50, "brb, pizza's here", listOf("casual")),
      ToneSample(
        51,
        "You did an amazing job on the presentation, everyone loved it!",
        listOf("friendly"),
      ),
      ToneSample(52, "Use the template. Don't change the fonts.", listOf("direct")),
      ToneSample(
        53,
        "I could be wrong, but wasn't the deadline extended to the 20th?",
        listOf("uncertain"),
      ),
      ToneSample(
        54,
        "Mom's been taken to the hospital, can you come home as soon as you can",
        listOf("urgent"),
      ),
      ToneSample(
        55,
        "Please accept our sincere apologies for the error in your invoice.",
        listOf("apologetic", "formal"),
      ),
      ToneSample(56, "Seriously? You cancelled on me again, an hour before?", listOf("frustrated")),
      ToneSample(
        57,
        "Hi team, a quick heads-up that the parking lot will be repainted this Saturday.",
        listOf("friendly", "casual"),
      ),
      ToneSample(
        58,
        "Is it just me or does this sound off? Could totally be overthinking it.",
        listOf("uncertain", "casual"),
      ),
      ToneSample(59, "Answer the question. Yes or no.", listOf("direct", "frustrated")),
      ToneSample(
        60,
        "Sorry to bother you again, but the payment still hasn't come through and rent is due tomorrow.",
        listOf("urgent", "apologetic"),
      ),
    )
}
