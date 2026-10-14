package com.raghavsatyadev.grammarfix.engine

/**
 * The sample set for issue #41: fifty sentences with the correct version of each written down by
 * hand, used to measure how the shipping engine actually behaves.
 *
 * **Fifteen of these are already correct.** That is deliberate and it is the point of the whole
 * exercise. A sample set where every sentence contains a mistake cannot tell us whether the model
 * rewrites text that was fine — there is always something there to fix, so rewriting everything
 * scores well. The clean sentences are the control group, and several of them are casual, with
 * contractions and no formal structure, because those are exactly the sentences a model trained to
 * produce polished prose is tempted to "improve".
 *
 * The other thirty-five are the mistakes people make typing on a phone. Most mix several error
 * types in one sentence, the way real messages do; [category] names the dominant one.
 */
data class GecSample(
  val id: Int,
  /** Dominant error type, or [CLEAN] when the sentence needs no change at all. */
  val category: String,
  val input: String,
  /** What a careful human editor would produce. Equal to [input] for a clean sample. */
  val expected: String,
) {
  val isClean: Boolean
    get() = input == expected
}

object GecEvalDataset {

  const val CLEAN = "clean"

  val all: List<GecSample> =
    listOf(
      // ---- Already correct. Any edit here is a false alarm. ----
      clean(1, "I'll be there in ten minutes."),
      clean(2, "Can you send me the file when you get a chance?"),
      clean(3, "The meeting is at 3 p.m. tomorrow."),
      clean(4, "Thanks, that worked perfectly."),
      clean(5, "We're running a bit late, sorry."),
      clean(6, "Let me know if you need anything else."),
      clean(7, "I think we should wait until Monday."),
      clean(8, "She said she'd call back this afternoon."),
      clean(9, "The build passed on the second try."),
      clean(10, "Do you want me to pick up lunch?"),
      clean(11, "It's been a long week."),
      clean(12, "I don't have the numbers yet."),
      clean(13, "Could you double-check the invoice, please?"),
      clean(14, "He's already left for the airport."),
      clean(15, "That's fine by me."),

      // ---- Spelling ----
      GecSample(
        16,
        "spell",
        "i recieved your mesage yesterday",
        "I received your message yesterday.",
      ),
      GecSample(
        17,
        "spell",
        "can you chek the adress on the invoice",
        "Can you check the address on the invoice?",
      ),
      GecSample(
        18,
        "spell",
        "the resturant was realy busy tonight",
        "The restaurant was really busy tonight.",
      ),
      GecSample(19, "spell", "sorry for the inconvienence", "Sorry for the inconvenience."),
      GecSample(20, "spell", "thier flight got delayed agian", "Their flight got delayed again."),

      // ---- Subject-verb agreement ----
      GecSample(
        21,
        "agreement",
        "the cats is sleeping on the sofa",
        "The cats are sleeping on the sofa.",
      ),
      GecSample(22, "agreement", "he don't want to come", "He doesn't want to come."),
      GecSample(23, "agreement", "there is three files missing", "There are three files missing."),

      // ---- Verb tense and form ----
      GecSample(24, "tense", "i go to the office yesterday", "I went to the office yesterday."),
      GecSample(
        25,
        "tense",
        "she have been waiting since morning",
        "She has been waiting since morning.",
      ),
      GecSample(26, "tense", "we was discussing the launch", "We were discussing the launch."),

      // ---- Articles ----
      GecSample(
        27,
        "article",
        "can you send me file before lunch",
        "Can you send me the file before lunch?",
      ),
      GecSample(
        28,
        "article",
        "he is engineer at our company",
        "He is an engineer at our company.",
      ),

      // ---- Prepositions ----
      GecSample(29, "preposition", "i will call you in monday", "I will call you on Monday."),
      GecSample(30, "preposition", "she is good in maths", "She is good at maths."),

      // ---- Plurals ----
      GecSample(
        31,
        "plural",
        "i bought two apple and three banana",
        "I bought two apples and three bananas.",
      ),
      GecSample(
        32,
        "plural",
        "there were many childs at the park",
        "There were many children at the park.",
      ),

      // ---- Punctuation ----
      GecSample(33, "punctuation", "hello how are you", "Hello, how are you?"),
      GecSample(34, "punctuation", "yes i agree lets do it", "Yes, I agree. Let's do it."),
      GecSample(
        35,
        "punctuation",
        "its been a long day hasnt it",
        "It's been a long day, hasn't it?",
      ),
      GecSample(
        36,
        "punctuation",
        "thanks john ill see you tomorrow",
        "Thanks, John. I'll see you tomorrow.",
      ),

      // ---- Capitalisation ----
      GecSample(
        37,
        "capitalisation",
        "we are meeting in london on friday",
        "We are meeting in London on Friday.",
      ),
      GecSample(
        38,
        "capitalisation",
        "my name is raghav and i work at google",
        "My name is Raghav and I work at Google.",
      ),

      // ---- Apostrophes and contractions ----
      GecSample(
        39,
        "apostrophe",
        "i cant find the keys anywhere",
        "I can't find the keys anywhere.",
      ),
      GecSample(40, "apostrophe", "thats not what i meant", "That's not what I meant."),
      GecSample(41, "apostrophe", "your going to love this", "You're going to love this."),

      // ---- Word order ----
      GecSample(
        42,
        "word-order",
        "always he is late for meetings",
        "He is always late for meetings.",
      ),
      GecSample(
        43,
        "word-order",
        "do you know where is the report",
        "Do you know where the report is?",
      ),

      // ---- Several errors at once, the way real messages arrive ----
      GecSample(44, "mixed", "i dont have no time for this", "I don't have any time for this."),
      GecSample(
        45,
        "mixed",
        "hi team just wanted to let you no that the deploy is done and everthing looks good",
        "Hi team, just wanted to let you know that the deploy is done and everything looks good.",
      ),
      GecSample(
        46,
        "mixed",
        "the client have ask for a update on the timeline",
        "The client has asked for an update on the timeline.",
      ),
      GecSample(
        47,
        "mixed",
        "please confirm if your available for the call tommorow at 4",
        "Please confirm if you're available for the call tomorrow at 4.",
      ),
      GecSample(
        48,
        "mixed",
        "we need too finish this befor friday otherwise the launch will be delay",
        "We need to finish this before Friday, otherwise the launch will be delayed.",
      ),
      GecSample(
        49,
        "mixed",
        "i seen the email but i didnt had time to reply",
        "I saw the email but I didn't have time to reply.",
      ),
      GecSample(
        50,
        "mixed",
        "this is the most fastest way to do it",
        "This is the fastest way to do it.",
      ),
    )

  val clean: List<GecSample> = all.filter { it.isClean }

  private fun clean(id: Int, text: String) = GecSample(id, CLEAN, text, text)
}
