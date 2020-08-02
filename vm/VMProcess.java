package nachos.vm;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Arrays;
import java.util.LinkedList;
import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;

/**
 * A <tt>UserProcess</tt> that supports demand-paging.
 */
public class VMProcess extends UserProcess {
	/**
	 * Allocate a new process.
	 */
	public VMProcess() {
		super();
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() {
		super.saveState();
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() {
		super.restoreState();
	}

	/**
	 * Initializes page tables for this process so that the executable can be
	 * demand-paged.
	 *
	 * @return	<tt>true</tt> if successful.
	 */
	protected boolean loadSections() {
		return super.loadSections();
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
		super.unloadSections();
	}    

	/**
	 * Handle a user exception. Called by
	 * <tt>UserKernel.exceptionHandler()</tt>. The
	 * <i>cause</i> argument identifies which exception occurred; see the
	 * <tt>Processor.exceptionZZZ</tt> constants.
	 *
	 * @param	cause	the user exception that occurred.
	 */
	public void handleException(int cause) {
		Processor processor = Machine.processor();

		switch (cause) {
		default:
			super.handleException(cause);
			break;
		}
	}
	
	/**
	 * Loads global page table.
	 *
	 * @param vpn:	int being the virtual page number
	 * @return: 	TranslationEntry object
	 */
	protected TranslationEntry lazyLoader(int vpn) {
		if (vpn < 0 || vpn >= numPages) {
			return null;
		}
		//valid vpn in param if reached
		TranslationEntry transEnt = pageTable[vpn];
		if (transEnt == null) {
			TranslationEntry newTransEnt = new TranslationEntry(vpn, -1, true, false, false, false);
			if (vpn < numPages - stackPages - 1) {
				int sec = 0;
				for (; sec < coff.getNumSections(); ++sec) {
					if (coff.getSection(sec).getFirstVPN() > vpn) {
						break;
					}
				}
				CoffSection sect = coff.getSection(sec - 1);
				newTransEnt.readOnly = sect.isReadOnly();
				insertPg(new Couple(pid, vpn), newTransEnt);
				sect.loadPage(vpn - sect.getFirstVPN(), newTransEnt.ppn);
			} else {
				insertPg(new Couple(pid, vpn), newTransEnt);
				Arrays.fill(Machine.processor().getMemory(),
						newTransEnt.ppn * pageSize, (newTransEnt.ppn + 1) * pageSize, (byte) 0);
			}
			transEnt = newTransEnt;
		}
		
		return transEnt;
	}

	/**
	 * Inserts TranslationEntry object into globalPT.
	 *
	 * @param aCouple:	Couple object being (int pid, int vpn)
	 * @param transEnt:	TranslationEntry object to be added
	 */
	public void insertPg(Couple aCouple, TranslationEntry transEnt) {
		lock.acquire();

		findFreePg(transEnt);
		transEnt.valid = true;
		globalPT.put(aCouple, transEnt);
		invertedPT[transEnt.ppn] = aCouple;

		lock.release();
	}

	/**
	 * Finds a free page by calling freePg() or creates one
	 * by removing the first element in freePgs list.
	 *
	 * @param transEnt:	TraslationEntry object to have ppn updated
	 */
	private void findFreePg(TranslationEntry transEnt) {
		if (freePgs.isEmpty()) {
			int ppn = freePg();
			transEnt.ppn = ppn;
		}
		else
			transEnt.ppn = freePgs.removeFirst();
	}

	/**
	 * Selects victim to be evicted.
	 *
	 * @return: int of evicted
	 */
	private int freePg() {
		pinLock.acquire();

		while (pinnedPgs.size() == Machine.processor().getNumPhysPages()) {
			pinnedCondition.sleep();
		}

		pinLock.release();

		while (true) {
			TranslationEntry transEnt = globalPT.get(invertedPT[v]);
			if (transEnt != null) {
				if (!transEnt.used && !pinnedPgs.contains(invertedPT[v])) {
					int evicted = v;
					v = (v + 1) % invertedPT.length;
					return evicted;
				}
				transEnt.used = false;
			}
			v = (v + 1) % invertedPT.length;
		}
	}
	
	/**
	 * Gets page based on (pid, vpn) couple and adds pairing to globalPT.
	 *
	 * @param aCouple:	Couple object being (pid, vpn)
	 * @return:			TranslationEntry object based on aCouple
	 */
	public TranslationEntry getPg(Couple aCouple) {
        lock.acquire();
        
        TranslationEntry transEnt = globalPT.get(aCouple);
        if (transEnt != null && !transEnt.valid) {
        	findFreePg(transEnt);
            globalPT.put(aCouple, transEnt);
            invertedPT[transEnt.ppn] = aCouple;
        }

        lock.release();
        
        return transEnt;
    }
	
	/**
	 * Removes page based on (pid, vpn) couple and removes from globalPT.
	 *
	 * @param aCouple:	Couple object being (pid, vpn)
	 */
	void removePg(Couple aCouple) {
        lock.acquire();

        TranslationEntry transEnt = globalPT.get(aCouple);
        if (transEnt == null) {
            lock.release();
            return;
        } 
        transEnt.valid = false;
        freePgs.add(transEnt.ppn);
        invertedPT[transEnt.ppn] = null;
        globalPT.remove(aCouple);

        for (int i = 0; i < Machine.processor().getTLBSize(); ++i) {
            TranslationEntry tEnt = Machine.processor().readTLBEntry(i);
            if (tEnt.ppn == transEnt.ppn)
            	tEnt.valid = false;
            Machine.processor().writeTLBEntry(i, tEnt);
        }

        lock.release();
    }
	
	/**
	 * Checks to see if ppn is valid.
	 *
	 * @param ppn:	physical page number to check validity
	 * @return:		boolean being true or false based on validity
	 */
	public boolean isPgValid(int ppn) {
        TranslationEntry transEnt = globalPT.get(invertedPT[ppn]);
        boolean bool = (transEnt != null) && (transEnt.valid);
        return bool;
    }
	
	/**
	 * Adds Couple set being (pid, vpn) to pinned list.
	 *
	 * @param aCouple:	Couple object being (pid, vpn)
	 */
	void pinPage(Couple aCouple) {
        pinLock.acquire();
        pinnedPgs.add(aCouple);
        pinLock.release();
    }

	/**
	 * Removes Couple set being (pid, vpn) from pinned list.
	 *
	 * @param aCouple:	Couple object being (pid, vpn)
	 */
    void unpinPage(Couple aCouple) {
        pinLock.acquire();
        pinnedPgs.remove(aCouple);
        pinnedCondition.wakeAll();
        pinLock.release();
    }

    /**
	 * Class for Couple object being a set (pid, vpn).
	 */
	private class Couple{
		private int pid;
		private int vpn;
		/**
		 * Creates a Couple set being (pid, vpn).
		 *
		 * @param pid:	int being the process id number
		 * @param vpn:	int being the virtual page number
		 */
		public Couple(int pid, int vpn) {
			this.pid = pid;
			this.vpn = vpn;
		}
	}

	//global variables
	public static HashMap<Couple, TranslationEntry> globalPT = new HashMap<Couple, TranslationEntry>(Machine.processor().getNumPhysPages());
	public static Couple[] invertedPT = new Couple[Machine.processor().getNumPhysPages()];
	
	//private variables
	private static final int pageSize = Processor.pageSize;
	private static final char dbgProcess = 'a';
	private static final char dbgVM = 'v';
	private int v = 0;
	private LinkedList<Integer> freePgs = new LinkedList<>();
	private Lock lock = new Lock();
	private Lock pinLock = new Lock();
	private HashSet<Couple> pinnedPgs;
	private Condition pinnedCondition = new Condition(pinLock);
	
}
