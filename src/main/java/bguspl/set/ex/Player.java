package bguspl.set.ex;

import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import bguspl.set.Config;
import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;
    private final Dealer dealer;
    protected final BlockingQueue<Integer> inputQueue;
    protected long freezeTimeLeft;
    private boolean iAmMaster;
    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.dealer = dealer;
        this.table = table;
        this.id = id;
        this.human = human;
        this.inputQueue = new ArrayBlockingQueue<>(3, true);
        this.iAmMaster = false;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public synchronized void run() {        
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        playerThread = Thread.currentThread();
        System.out.println("thread " + Thread.currentThread().getName() + " starting."); //Added NoteLine
        if (!human && iAmMaster) createArtificialIntelligence();

        while (!terminate) {
            try{
                if(handleKeyPress()){
                    wait();
                    handleFreeze();
                    inputQueue.clear();
                }
            }
            catch (InterruptedException ignored){}            
        }
        if (!human && iAmMaster)
         try { 
            aiThread.join(); 
        } catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");

            Random r = new Random();
            Player[] aiPlayers = dealer.getAiPlayers();
            while (!terminate) {
                for(Player ai : aiPlayers){
                    ai.keyPressed(r.nextInt(env.config.tableSize));
                }
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        if(!human && iAmMaster) aiThread.interrupt();
        playerThread.interrupt();        
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if (inputQueue.remainingCapacity() > 0 && table.readLock.tryLock()) {
            try {
                inputQueue.put(slot);
            } catch (InterruptedException ignored) {
            }
            table.readLock.unlock();
        }
    }

    private boolean handleKeyPress() throws InterruptedException {
        int slot = inputQueue.take();
        boolean sentToDealer = false;
        table.readLock.lock();
        //In case of penalty, accept ONLY key presses that remove one of the current tokens
        while (table.playerTokensIsFeatureSize(id) && !table.playerAlreadyPlacedThisToken(id, slot)) {
            slot = inputQueue.take();
        }
        if (table.playerAlreadyPlacedThisToken(id, slot)) {
            if (!table.removeToken(id, slot)) {
                System.out.println("Couldn't remove player " + id + " token in slot " + slot);
            }

        } else {
            table.placeToken(id, slot);
            //In case it's a set size - send to dealer to check
            if (table.playerTokensIsFeatureSize(id)) {
                dealer.checkMySet(id);
                sentToDealer = true;
            }
        }
        table.readLock.unlock();
        return sentToDealer;
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public synchronized void point() {
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        score++;
        env.ui.setScore(id, score);
        setFreeze(env.config.pointFreezeMillis);
        wakeUp();
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public synchronized void penalty() {
        setFreeze(env.config.penaltyFreezeMillis);
        wakeUp();
    }

    public synchronized void wakeUp() {
       notify();
    }

    public boolean isHuman() {
        return human;
    }

    public int score() {
        return score;
    }

    protected BlockingQueue<Integer> getInputQueue(){
        return inputQueue;
    }

    public void joinPlayerThread() {
        try {
            playerThread.join();
        }   catch (InterruptedException ignored) {}
    }

    public void setFreeze(long time) {
        freezeTimeLeft = time;
    }

    private void handleFreeze() throws InterruptedException {
        while (freezeTimeLeft > 0) {
            env.ui.setFreeze(id, freezeTimeLeft);
            long sleepTime = Math.min(freezeTimeLeft, env.config.pointFreezeMillis);
            setFreeze(freezeTimeLeft - sleepTime);
            Thread.sleep(sleepTime);
        }
        env.ui.setFreeze(id, 0);
    }

    public void setMaster() {
        iAmMaster = true;
    }

    

}
