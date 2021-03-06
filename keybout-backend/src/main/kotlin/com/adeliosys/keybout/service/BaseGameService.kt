package com.adeliosys.keybout.service

import com.adeliosys.keybout.model.*
import com.adeliosys.keybout.util.sendMessage
import com.adeliosys.keybout.util.userName
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.web.socket.WebSocketSession
import java.time.Instant

/**
 * Base class for the various game types.
 */
abstract class BaseGameService(
        private val dictionaryService: DictionaryService,
        private val calculusService: CalculusService,
        protected val awardService: AwardService,
        private val scoreService: ScoreService,
        private val scheduler: ThreadPoolTaskScheduler) {

    var id: Long = 0

    var style: GameStyle = GameStyle.REGULAR

    private var roundsCount: Int = 0

    var language: Language = Language.EN

    private var declaredWordsCount: Int = 0

    /**
     * The effective number of words for each round,
     * based on the declared number of words and the game type.
     */
    var effectiveWordsCount: Int = 0

    var difficulty: Difficulty = Difficulty.EASY

    /**
     * Name of the player that starts the next round.
     */
    var manager: String = ""

    /**
     * All game players, including the manager.
     */
    val players: MutableList<WebSocketSession> = mutableListOf()

    /**
     * Round and game scores by user name, updated as the words are assigned.
     */
    protected val userScores: MutableMap<String, Score> = mutableMapOf()

    /**
     * Ordered round scores, updated at the end of the round.
     */
    private var roundScores: List<Score> = emptyList()

    /**
     * Ordered game scores, updated at the end of the game.
     */
    private var gameScores: List<Score> = emptyList()

    /**
     * ID of the current round.
     */
    protected var roundId = 0

    /**
     * Timestamp of the beginning of the current round,
     * used to compute the words/min.
     */
    private var roundStart = 0L

    /**
     * Used by the UI.
     * @return the game type such as 'capture' or 'race'.
     */
    abstract fun getGameType(): String

    /**
     * One-time initialization. Should be in a constructor or Kotlin init block,
     * but would not be Spring friendly since this class is a Spring service.
     */
    open fun initializeGame(gameDescriptor: GameDescriptor, players: MutableList<WebSocketSession>) {
        id = gameDescriptor.id

        style = gameDescriptor.style

        roundsCount = gameDescriptor.rounds

        language = gameDescriptor.language

        declaredWordsCount = gameDescriptor.wordsCount

        difficulty = gameDescriptor.difficulty

        manager = gameDescriptor.creator

        this.players.addAll(players)

        players.forEach { userScores[it.userName] = Score(it.userName) }
    }

    fun generateWords(): List<Word> {
        return when (style) {
            GameStyle.CALCULUS -> calculusService.generateOperations(effectiveWordsCount, difficulty).first
            else -> dictionaryService.generateWords(language, effectiveWordsCount, style, difficulty).first
        }
    }

    /**
     * Start the countdown for the next round.
     */
    open fun startCountdown() {
        roundId++

        // Reset the players scores
        userScores.forEach { (_, s) -> s.resetPoints() }

        // Notify players to display the countdown
        sendMessage(players, GameStartNotification())

        // Notify playing users when the round begins
        schedule(5L) { startPlay() }
    }

    /**
     * Actually start the round.
     */
    open fun startPlay() {
        roundStart = System.currentTimeMillis()

        // Make sure that the round will expire after some time.
        val currentRoundId = roundId
        schedule(style.getExpirationDuration(declaredWordsCount)) { claimRemainingWords(currentRoundId) }
    }

    private fun schedule(delay: Long, task: () -> Unit) {
        scheduler.schedule(task, Instant.now().plusSeconds(delay))
    }

    /**
     * Utility method that returns a UI friendly map of words,
     * i.e. the map key is the word value and the map value is the assigned user name (or empty).
     */
    fun getWordsDto(words: Map<String, Word>): List<Array<String>> = words.map { arrayOf(it.key, it.value.userName, it.value.display) }.toList()

    /**
     * Update the game state after a player completely typed a word.
     * @return true if the game is over
     */
    abstract fun claimWord(session: WebSocketSession, value: String): Boolean

    /**
     * Game rounds naturally expire after some time. When it happens, this method is called
     * to notify the players.
     */
    abstract fun claimRemainingWords(roundId: Int)

    fun isGameOver() = gameScores.isNotEmpty() && gameScores[0].victories >= roundsCount

    /**
     * Update round and game scores.
     */
    fun updateScores() {
        // Update the words/min and best words/min
        userScores.values.forEach { it.updateSpeeds(roundStart) }

        // Get the sorted round scores
        roundScores = userScores.values.sortedWith(compareBy({ -it.points }, { -it.speed }))

        // Give 1 victory to the round winner
        roundScores[0].incrementVictories()

        // Get the sorted game scores
        gameScores = userScores.values.sortedWith(compareBy({ -it.victories }, { -it.bestSpeed }, { it.latestVictoryTimestamp }))
    }

    /**
     * Update the top scores.
     */
    fun updateTopScores() {
        scoreService.updateTopScores(style, language, difficulty, roundScores, effectiveWordsCount)
    }

    /**
     * @return UI friendly round scores.
     */
    fun getRoundScoresDto() = roundScores.map { ScoreDto(it.userName, it.points, it.speed, it.awards) }

    /**
     * @return UI friendly game scores.
     */
    fun getGameScoresDto() = gameScores.map { ScoreDto(it.userName, it.victories, it.bestSpeed, null) }

    /**
     * A user disconnected, remove him from the game.
     *
     * @return true if the game has ended
     */
    @Synchronized
    open fun removeUser(session: WebSocketSession): Boolean {
        if (players.remove(session)) {
            if (players.size > 0 && session.userName == manager) {
                // Choose a new manager
                manager = players[0].userName
                sendMessage(players, ManagerNotification(manager))
            }

            // The game has ended because there is no player left
            return players.size <= 0
        }
        return false
    }
}
