package nachos.threads;

import nachos.machine.*;

/**
 * A <i>communicator</i> allows threads to synchronously exchange 32-bit
 * messages. Multiple threads can be waiting to <i>speak</i>,
 * and multiple threads can be waiting to <i>listen</i>. But there should never
 * be a time when both a speaker and a listener are waiting, because the two
 * threads can be paired off at this point.
 */
public class Communicator {
    /**
     * Allocate a new communicator.
     */
    public Communicator() {
        //condition variables
        lock = new Lock();
        speaker = new Condition(lock);
        listener = new Condition(lock);
        wordState = false;
        listenerState = false;
        speakerState = false;
    }

    /**
     * Wait for a thread to listen through this communicator, and then transfer
     * <i>word</i> to the listener.
     *
     * <p>
     * Does not return until this thread is paired up with a listening thread.
     * Exactly one listener should receive <i>word</i>.
     *
     * @param	word	the integer to transfer.
     */
    public void speak(int word) {
        //Get control over this lock
        lock.acquire();
        
        //Speaker is now running
        speakerState = true;

        //Check if listener is still running or if speaker has a new word
        while (listenerState || !wordState) {
            //Put the speaker to sleep
            speaker.sleep();
        }
        //Update this.word with the new word parameter
        this.word = word;
        
        //New word came in
        wordState = true;
        
        //Wake up all the listener threads
        listener.wakeAll();
        
        //Speaker is no longer speaking
        speakerState= false;
        
        //Release this lock for other threads to use
        lock.release();
    }

    /**
     * Wait for a thread to speak through this communicator, and then return
     * the <i>word</i> that thread passed to <tt>speak()</tt>.
     *
     * @return	the integer transferred.
     */    
    public int listen() {
        //Get control over this lock
        lock.acquire();
        
        //Listener is now running
        listenerState = true;
        
        //while the speaker has a word to speak
        while (wordState) {
        	//put the listener to sleep
            listener.sleep();
            //wake up all the speaker threads
            speaker.wakeAll();
        }
        //Receive word from the speaker
        int msgReceived = this.word;

        //Reset this.word until the speaker assigns it a new value
        this.word = 0;

        //Listener is no longer listening
        listenerState = false;
        
         //Release this lock for other threads to use
         lock.release();

        return msgReceived;
    }
    private Lock lock;
    private Condition listener;
    private Condition speaker;
    private int word = 0;
    private boolean wordState;
    private boolean listenerState;
    private boolean speakerState;
}