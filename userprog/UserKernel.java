package nachos.userprog;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import java.util.List;
import java.util.LinkedList;
import java.util.Collections;
import java.util.HashMap;

/**
 * A kernel that can support multiple user processes.
 */
public class UserKernel extends ThreadedKernel {
	/**
	 * Allocate a new user kernel.
	 */
	public UserKernel() {
		super();
	}

	/**
	 * Initialize this kernel. Creates a synchronized console and sets the
	 * processor's exception handler.
	 */
	public void initialize(String[] args) {
		super.initialize(args);
		console = new SynchConsole(Machine.console());
		
		pgTable = new LinkedList<>();	
		for(int i = 0; i < Machine.processor().getNumPhysPages(); ++i) {
			pgTable.add(i);
		}
		synchPgTable = Collections.synchronizedList(pgTable);

		Machine.processor().setExceptionHandler(new Runnable() {
			public void run() { exceptionHandler(); }
		});
	}

	/**
	 * Test the console device.
	 */	
	public void selfTest() {
		super.selfTest();

		System.out.println("Testing the console device. Typed characters");
		System.out.println("will be echoed until q is typed.");

		char c;

		do {
			c = (char) console.readByte(true);
			console.writeByte(c);
		}
		while (c != 'q');

		System.out.println("");
	}

	/**
	 * Returns the current process.
	 *
	 * @return	the current process, or <tt>null</tt> if no process is current.
	 */
	public static UserProcess currentProcess() {
		if (!(KThread.currentThread() instanceof UThread))
			return null;

		return ((UThread) KThread.currentThread()).process;
	}

	/**
	 * The exception handler. This handler is called by the processor whenever
	 * a user instruction causes a processor exception.
	 *
	 * <p>
	 * When the exception handler is invoked, interrupts are enabled, and the
	 * processor's cause register contains an integer identifying the cause of
	 * the exception (see the <tt>exceptionZZZ</tt> constants in the
	 * <tt>Processor</tt> class). If the exception involves a bad virtual
	 * address (e.g. page fault, TLB miss, read-only, bus error, or address
	 * error), the processor's BadVAddr register identifies the virtual address
	 * that caused the exception.
	 */
	public void exceptionHandler() {
		Lib.assertTrue(KThread.currentThread() instanceof UThread);

		UserProcess process = ((UThread) KThread.currentThread()).process;
		int cause = Machine.processor().readRegister(Processor.regCause);
		process.handleException(cause);
	}

	/**
	 * Start running user programs, by creating a process and running a shell
	 * program in it. The name of the shell program it must run is returned by
	 * <tt>Machine.getShellProgramName()</tt>.
	 *
	 * @see	nachos.machine.Machine#getShellProgramName
	 */
	public void run() {
		super.run();

		UserProcess process = UserProcess.newUserProcess();

		String shellProgram = Machine.getShellProgramName();	
		Lib.assertTrue(process.execute(shellProgram, new String[] { }));

		KThread.currentThread().finish();
	}

	/**
	 * Terminate this kernel. Never returns.
	 */
	public void terminate() {
		super.terminate();
	}

	/**
	 * @return page number of free memory space
	 */
	public static int getFreeMem() {	
		int pgNum = 0;
		Machine.interrupt().disable();
		if(!synchPgTable.isEmpty()) {
			pgNum = synchPgTable.get(0);
			synchPgTable.remove(0);
		}
		Machine.interrupt().enable();
		
		return pgNum;
	}

	/**
	 * add freed memory page to linked list
	 * 
	 * @param pageNum: page number of freed memory to be added to pgTable
	 */
	public static void addFreeMemToTable(int pageNum) {									
		Lib.assertTrue(pageNum >= 0 && pageNum < Machine.processor().getNumPhysPages());
		Machine.interrupt().disable();
		
		if(synchPgTable.contains(pageNum)) {
			return;
		}
		synchPgTable.add(pageNum);
		
		Machine.interrupt().enable();	
	}
	
	
	/**
	 * get the next process' id number
	 * 
	 * @return pid number
	 */
	public static int getPid() {
		Machine.interrupt().disable();
		int pid = nextPid++;
		Machine.interrupt().enable();
		return pid;
	}

	/**
	 * get specific process from pid number
	 * 
	 * @param pid of wanted process
	 * @return process with the desired pid number
	 */
	public static UserProcess getProcessById(int pid) {
		return processHM.get(pid);
	}

	/**
	 * @return number of current processes 
	 */
	public static int getCurrentProcesses() {
		return processHM.size();
	}

	/**
	 * add new process to HashMap
	 * 
	 * @param pid: id of process to be added 
	 * @param process: process to be added and linked to pid
	 */
	public static void addProcessToHM(int pid, UserProcess process) {
		Machine.interrupt().disable();
		if(processHM.containsKey(pid)) {
			return;
		}
		else {
			processHM.put(pid, process);
		}
		Machine.interrupt().enable();
	}

	/**
	 * remove process from HashMap
	 * 
	 * @param pid: pid number to remove corresponding process
	 */
	public static void removeProcess(int pid) {
		Machine.interrupt().disable();
		if(processHM.containsKey(pid)) {
			processHM.remove(pid);
		}
		else {
			return;
		}
		Machine.interrupt().enable();
	}

	//global variables
	public static SynchConsole console;
	public static List<Integer> synchPgTable;
	
	//private variables
	private static LinkedList<Integer> pgTable;
	private static int nextPid = 1;
	private static HashMap<Integer, UserProcess> processHM = new HashMap<Integer, UserProcess>();

	// dummy variable to make javac smarter
	private static Coff dummy1 = null;
}
