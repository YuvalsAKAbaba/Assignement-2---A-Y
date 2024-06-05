package bguspl.set.ex;

import bguspl.set.Env;
import bguspl.set.UtilImpl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    // added enum class
    /**
     * representing the game mode activated
     */
    public enum Mode{
        NEGETIVE,ZERO,POSITIVE
    }

    private Mode mode;


    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;
    private final Player[] aiPlayers;
    ConcurrentLinkedQueue<Integer> playersIdWithSet;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;
    
    /**
     * Dealer thread
     */
    private Thread dealerThread;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        this.deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        this.aiPlayers = new Player[env.config.computerPlayers];
        this.playersIdWithSet = new ConcurrentLinkedQueue<>();
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        dealerThread = Thread.currentThread();

        table.writeLock.lock();
        createPlayersThread();
        
        while (!shouldFinish()) {
            shuffleDeck();
            placeCardsOnTable();
            table.writeLock.unlock();
            updateTimerDisplay(true);
            timerLoop();
            updateTimerDisplay(false);
            table.writeLock.lock();
            removeAllCardsFromTable();
        }
        table.writeLock.unlock();

        announceWinners();
        terminate();
        joinPlayers();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    private void createPlayersThread() {
        // constructing aiPlayers array and setting master to implement master-slave
        int aiIndex = 0;
        for (Player ai : this.players) {
            if (!ai.isHuman()) {
                aiPlayers[aiIndex++] = ai;
            }
        }
        if (env.config.computerPlayers > 0) aiPlayers[0].setMaster();
        for (int i = 0; i < players.length; i++) {
            new Thread(players[i], "player " + i).start();
        }
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            while (!(playersIdWithSet.isEmpty())) {
                if (playerFoundSet()) {
                    removeCardsFromTable();
                    //in origin there was placeCardsOnTable here but moved it to removeCardsFromTable
                }
            }
        }
    }

    private void terminateAllPlayers() {
        for (int i = players.length - 1; i >= 0; i--) {
            players[i].terminate();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        terminateAllPlayers();
        dealerThread.interrupt();        
    }

    private void joinPlayers() {
        for (Player player : players) {
            player.joinPlayerThread();
        }
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        List<Integer> allCards = deck;
        allCards.addAll(table.tableCardsToList());
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        table.writeLock.lock();
        Integer playerId = playersIdWithSet.remove();
        Integer[] playerSetCards = table.playerCards(playerId);
        checkCollision(playerSetCards, playerId);
        updateTableSetWasClaimed(playerSetCards);
        updateTimerDisplay(true);        
        placeCardsOnTable(); 
        table.writeLock.unlock();
        players[playerId].point();
    }

    private boolean playerFoundSet(){
        while(!playersIdWithSet.isEmpty()){
            Integer playerId = playersIdWithSet.peek();
            Integer[] playerSetCards = table.playerCards(playerId);
            if(env.util.testSet(convertIntegerArrToIntArr(playerSetCards))){
                return true;
            }
            else{
                playersIdWithSet.remove();          
                players[playerId].penalty();
            }            
        }
        return false;
    }

    public synchronized void checkMySet(int id) {
        playersIdWithSet.add(id);
        notifyAll();
    }

    private int[] convertIntegerArrToIntArr(Integer[] arr){
        int[] converted = new int[arr.length];
        for(int i = 0; i < arr.length; i++){
            converted[i] = arr[i];
        }
        return converted;
    }
    
    private void updateTableSetWasClaimed(Integer[] cards) {
        table.writeLock.lock();
        for (Integer card : cards) {
            table.removeCard(table.getSlotFromCard(card));
        }
        table.writeLock.unlock();
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        while(table.hasEmptySlot() && (!deck.isEmpty())){
            table.placeCard(deck.remove(0));
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private synchronized void sleepUntilWokenOrTimeout() {
`       try {
            this.wait(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}
       
    }

    //added function
    /**
     * Checks if there is a valid set approved, which collides with other players former placed setRequests
     * connected to 'removeCardsFromTable'
     */    
    private void removeCollisionsForGivenSet(Integer[] validatedSet) {
        playersIdWithSet.removeIf(playerIdToCheck -> checkCollision(validatedSet, playerIdToCheck));
    }

    private boolean checkCollision(Integer[] validatedSet, Integer playerIdToCheck) {
        Set<Integer> setOfGuess = new HashSet<>(Arrays.asList(validatedSet));

        for (Integer element : table.playerCards(playerIdToCheck) ) {
            if (setOfGuess.contains(element)) {
                players[playerIdToCheck].wakeUp();
                return true; // collision found
            }
        }
        return false; // no collision
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) { 
        if (reset) reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
        long timeLeft = reshuffleTime - System.currentTimeMillis() > 0 ? reshuffleTime - System.currentTimeMillis() : 0;
        env.ui.setCountdown(timeLeft, timeLeft < env.config.endGamePauseMillies);

    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        deck.addAll(table.tableCardsToList());
        table.removeAllCards();
        shuffleDeck();
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int maxScore = findMaxScore();
        ArrayList<Integer> winnersId = new ArrayList<>();
        for (int i = 0; i < players.length; i++) {
            if (players[i].score() == maxScore) {
                winnersId.add(players[i].id);
            }
        }
        env.ui.announceWinner(convertArrayListToArr(winnersId));
    }

    private int[] convertArrayListToArr(ArrayList<Integer> winnersId) {
        int[] arr = new int[winnersId.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = winnersId.get(i);
        }
        return arr;
    }

    private int findMaxScore() {
        int maxScore = players[0].score();
        for (int i = 1; i < players.length; i++) {
            int currScore = players[i].score();
            if (currScore > maxScore) {
                maxScore = currScore;
            }
        }
        return maxScore;
    }

    private void shuffleDeck() {
        Random rand = new Random();
        for (int i = 0; i < deck.size(); i++) {
            int j = rand.nextInt(deck.size());
            int temp = deck.get(i);
            deck.set(i, deck.get(j));
            deck.set(j, temp);
        }
    }

    public Player[] getAiPlayers(){
        return aiPlayers;
    } 


}
