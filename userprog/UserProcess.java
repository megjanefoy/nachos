package nachos.userprog;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;
import java.util.LinkedList;
import java.io.EOFException;

/**
 * Encapsulates the state of a user process that is not contained in its
 * user thread (or threads). This includes its address translation state, a
 * file table, and information about the program being executed.
 *
 * <p>
 * This class is extended by other classes to support additional functionality
 * (such as additional syscalls).
 *
 * @see	nachos.vm.VMProcess
 * @see	nachos.network.NetProcess
 */
public class UserProcess {
	/**
	 * Allocate a new process.
	 */
	public UserProcess() {
		//initialize open file array
		oFileArray = new FileDescriptor[maxNumFiles];
		//std in
		oFileArray[0] = new FileDescriptor(UserKernel.console.openForReading(), "STDIN"); 
		//std out
		oFileArray[1] = new FileDescriptor(UserKernel.console.openForWriting(), "STDOUT"); 
		pid = UserKernel.getPid();										//get process id
		UserKernel.addProcessToHM(pid, this);							//add process to hash map
	}

	/**
	 * Allocate and return a new process of the correct class. The class name
	 * is specified by the <tt>nachos.conf</tt> key
	 * <tt>Kernel.processClassName</tt>.
	 *
	 * @return	a new process of the correct class.
	 */
	public static UserProcess newUserProcess() {
		String pName = Machine.getProcessClassName ();

		if (pName.equals ("nachos.userprog.UserProcess")) {				//if a UserProcess
			return new UserProcess ();
		} else if (pName.equals ("nachos.vm.VMProcess")) {				//if a VMProcess
			return new VMProcess ();
		} else {														//will run out of file descriptors & throw exception
			return (UserProcess) Lib.constructObject(Machine.getProcessClassName());
		}
	}

	/**
	 * Execute the specified program with the specified arguments. Attempts to
	 * load the program, and then forks a thread to run it.
	 *
	 * @param	name	the name of the file containing the executable.
	 * @param	args	the arguments to pass to the executable.
	 * @return	<tt>true</tt> if the program was successfully executed.
	 */
	public boolean execute(String name, String[] args) {
		if (!load(name, args))
			return false;

		thread = new UThread(this);										//capture UThread
		thread.setName(name).fork();

		return true;
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() {
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() {
		Machine.processor().setPageTable(pageTable);
	}

	/**
	 * Read a null-terminated string from this process's virtual memory. Read
	 * at most <tt>maxLength + 1</tt> bytes from the specified address, search
	 * for the null terminator, and convert it to a <tt>java.lang.String</tt>,
	 * without including the null terminator. If no null terminator is found,
	 * returns <tt>null</tt>.
	 *
	 * @param	vaddr	the starting virtual address of the null-terminated
	 *			string.
	 * @param	maxLength	the maximum number of characters in the string,
	 *				not including the null terminator.
	 * @return	the string read, or <tt>null</tt> if no null terminator was
	 *		found.
	 */
	public String readVirtualMemoryString(int vaddr, int maxLength) {
		Lib.assertTrue(maxLength >= 0);

		byte[] bytes = new byte[maxLength+1];

		int bytesRead = readVirtualMemory(vaddr, bytes);

		for (int length=0; length<bytesRead; length++) {
			if (bytes[length] == 0)
				return new String(bytes, 0, length);
		}

		return null;
	}

	/**
	 * Transfer data from this process's virtual memory to all of the specified
	 * array. Same as <tt>readVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 *
	 * @param	vaddr	the first byte of virtual memory to read.
	 * @param	data	the array where the data will be stored.
	 * @return	the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data) {
		return readVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from this process's virtual memory to the specified array.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no
	 * data could be copied).
	 *
	 * @param	vaddr	the first byte of virtual memory to read.
	 * @param	data	the array where the data will be stored.
	 * @param	offset	the first byte to write in the array.
	 * @param	length	the number of bytes to transfer from virtual memory to
	 *			the array.
	 * @return	the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data, int offset,
			int length) {
		Lib.assertTrue(offset >= 0 && length >= 0 && offset+length <= data.length);

		byte[] memory = Machine.processor().getMemory();
		int addrOffset = Processor.offsetFromAddress(vaddr);			//get address offset
		int virtualPN = Processor.pageFromAddress(vaddr);				//get virtual page number
		TranslationEntry transEnt = pageTable[virtualPN];				//single translation entry
		transEnt.used = true;											//mark as used
		int physicalPN = transEnt.ppn;									//get physical page number
		int physicalAddr = (physicalPN*pageSize) + addrOffset;			//calculate physical address
		int wantedAmt = length;											//wanted number read
		int actualAmtRead = 0;											//actual number read

		//possible for data to be large and stored in many pages
		while (wantedAmt > 0 && virtualPN < numPages) {
			int readableAmt = pageSize - addrOffset;					//num bytes remaining in current page
			int amountRead = Math.min(length, readableAmt);				//either length of data or max allowed
			System.arraycopy(memory, physicalAddr, data, offset, amountRead);
			wantedAmt = wantedAmt - amountRead;							//calculate remaining amount wanted
			offset = offset + amountRead;								//calculate the end
			actualAmtRead = actualAmtRead + amountRead;					//calculate new actual amount read
			++virtualPN;												//get next virtual page number
			if (virtualPN < numPages) {									//if valid page number
				transEnt = pageTable[virtualPN];						//update translation entry
				physicalPN = transEnt.ppn;								//update physical page number
				physicalAddr = physicalPN*pageSize;						//update physical address
			}	
		}
		return actualAmtRead;
	}

	/**
	 * Transfer all data from the specified array to this process's virtual
	 * memory.
	 * Same as <tt>writeVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 *
	 * @param	vaddr	the first byte of virtual memory to write.
	 * @param	data	the array containing the data to transfer.
	 * @return	the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data) {
		return writeVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from the specified array to this process's virtual memory.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no
	 * data could be copied).
	 *
	 * @param	vaddr	the first byte of virtual memory to write.
	 * @param	data	the array containing the data to transfer.
	 * @param	offset	the first byte to transfer from the array.
	 * @param	length	the number of bytes to transfer from the array to
	 *			virtual memory.
	 * @return	the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data, int offset,
			int length) {
		Lib.assertTrue(offset >= 0 && length >= 0 && offset+length <= data.length);

		byte[] memory = Machine.processor().getMemory();

		int addrOffset = Processor.offsetFromAddress(vaddr);			//get address offset
		int virtualPN = Processor.pageFromAddress(vaddr);				//get virtual page number
		TranslationEntry transEnt = pageTable[virtualPN];				//single translation entry
		if (transEnt.readOnly) {
			return 0;													//do not continue if readOnly
		}
		transEnt.used = true;											//mark as used
		transEnt.dirty = true;											//mark as dirty
		int physicalPN = transEnt.ppn;									//get physical page number
		int physicalAddr = (physicalPN*pageSize) + addrOffset;			//calculate physical address
		int wantedAmt = length;											//wanted number write
		int actualAmtWritten = 0;										//actual number written

		//possible for data to be large and stored in many pages
		while (wantedAmt > 0 && virtualPN < numPages) {
			int writableAmt = pageSize - addrOffset;					//num bytes remaining in current page
			int amountWritten = Math.min(length, writableAmt);			//either length of data or max allowed
			System.arraycopy(data, offset, memory, physicalAddr, amountWritten);
			wantedAmt = wantedAmt - amountWritten;						//calculate remaining amount
			offset = offset + amountWritten;							//calculate end
			actualAmtWritten = actualAmtWritten + amountWritten;		//calculate new actual amount written
			++virtualPN;												//get next virtual page number
			if (virtualPN < numPages) {									//if page number is valid
				transEnt = pageTable[virtualPN];						//update translation entry
				physicalPN = transEnt.ppn;								//update physical page number
				physicalAddr = physicalPN*pageSize;						//update physical address
			}
		}
		return actualAmtWritten;
	}

	/**
	 * Load the executable with the specified name into this process, and
	 * prepare to pass it the specified arguments. Opens the executable, reads
	 * its header information, and copies sections and arguments into this
	 * process's virtual memory.
	 *
	 * @param	name	the name of the file containing the executable.
	 * @param	args	the arguments to pass to the executable.
	 * @return	<tt>true</tt> if the executable was successfully loaded.
	 */
	private boolean load(String name, String[] args) {
		Lib.debug(dbgProcess, "UserProcess.load(\"" + name + "\")");

		OpenFile executable = ThreadedKernel.fileSystem.open(name, false);
		if (executable == null) {
			Lib.debug(dbgProcess, "\topen failed");
			return false;
		}

		try {
			coff = new Coff(executable);
		}
		catch (EOFException e) {
			executable.close();
			Lib.debug(dbgProcess, "\tcoff load failed");
			return false;
		}

		// make sure the sections are contiguous and start at page 0
		numPages = 0;
		for (int s=0; s<coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);
			if (section.getFirstVPN() != numPages) {
				coff.close();
				Lib.debug(dbgProcess, "\tfragmented executable");
				return false;
			}
			numPages += section.getLength();
		}

		// make sure the argv array will fit in one page
		byte[][] argv = new byte[args.length][];
		int argsSize = 0;
		for (int i=0; i<args.length; i++) {
			argv[i] = args[i].getBytes();
			// 4 bytes for argv[] pointer; then string plus one for null byte
			argsSize += 4 + argv[i].length + 1;
		}
		if (argsSize > pageSize) {
			coff.close();
			Lib.debug(dbgProcess, "\targuments too long");
			return false;
		}

		// program counter initially points at the program entry point
		initialPC = coff.getEntryPoint();	

		// next comes the stack; stack pointer initially points to top of it
		numPages += stackPages;
		initialSP = numPages*pageSize;

		// and finally reserve 1 page for arguments
		numPages++;

		pageTable = new TranslationEntry[numPages];
		for (int i = 0; i < numPages; i++)
			pageTable[i] = new TranslationEntry(i, UserKernel.getFreeMem(), true, false, false, false);


		if (!loadSections())
			return false;

		// store arguments in last page
		int entryOffset = (numPages-1)*pageSize;
		int stringOffset = entryOffset + args.length*4;

		this.argc = args.length;
		this.argv = entryOffset;

		for (int i=0; i<argv.length; i++) {
			byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
			Lib.assertTrue(writeVirtualMemory(entryOffset,stringOffsetBytes) == 4);
			entryOffset += 4;
			Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) ==
					argv[i].length);
			stringOffset += argv[i].length;
			Lib.assertTrue(writeVirtualMemory(stringOffset,new byte[] { 0 }) == 1);
			stringOffset += 1;
		}

		return true;
	}

	/**
	 * Allocates memory for this process, and loads the COFF sections into
	 * memory. If this returns successfully, the process will definitely be
	 * run (this is the last step in process initialization that can fail).
	 *
	 * @return	<tt>true</tt> if the sections were successfully loaded.
	 */
	protected boolean loadSections() {
		if (numPages > Machine.processor().getNumPhysPages()) {
			coff.close();
			Lib.debug(dbgProcess, "\tinsufficient physical memory");
			return false;
		}

		// load sections
		for (int s=0; s<coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);

			Lib.debug(dbgProcess, "\tinitializing " + section.getName()
			+ " section (" + section.getLength() + " pages)");

			for (int i=0; i<section.getLength(); i++) {
				int vpn = section.getFirstVPN()+i;
				TranslationEntry transEnt = pageTable[vpn];
				transEnt.readOnly = section.isReadOnly();				//get read only status
				section.loadPage(i, transEnt.ppn);						//load page
			}
		}

		return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
		for (int i = 0; i < numPages; i++) {
			UserKernel.addFreeMemToTable(pageTable[i].ppn);  
			pageTable[i].valid = false;
		}
	}    

	/**
	 * Initialize the processor's registers in preparation for running the
	 * program loaded into this process. Set the PC register to point at the
	 * start function, set the stack pointer register to point at the top of
	 * the stack, set the A0 and A1 registers to argc and argv, respectively,
	 * and initialize all other registers to 0.
	 */
	public void initRegisters() {
		Processor processor = Machine.processor();

		// by default, everything's 0
		for (int i=0; i<processor.numUserRegisters; i++)
			processor.writeRegister(i, 0);

		// initialize PC and SP according
		processor.writeRegister(Processor.regPC, initialPC);
		processor.writeRegister(Processor.regSP, initialSP);

		// initialize the first two argument registers to argc and argv
		processor.writeRegister(Processor.regA0, argc);
		processor.writeRegister(Processor.regA1, argv);
	}

	/**
	 * Handle the halt() system call. 
	 */
	private int handleHalt() {
		if (pid == ROOT) {
			Machine.halt();
			Lib.assertNotReached("Machine.halt() did not halt machine!");
		}
		return 0;
	}

	/**
	 * Handle the exit() system call.
	 */
	private void handleExit(int sysStatus) {
		for (int i = 0; i < maxNumFiles; i++) {
			if (oFileArray[i] != null)									//close all open files
				handleClose(i);
		}

		while (pChildrenList != null && !pChildrenList.isEmpty()) {		//loop through list of children
			int cPid = pChildrenList.removeFirst();
			UserProcess pChild = UserKernel.getProcessById(cPid);
			if (pChild != null) {
				pChild.ppid = 0;
			}
		}

		exitStat = sysStatus;
		unloadSections();												//free memory
		if (UserKernel.getCurrentProcesses() == 1 || pid == ROOT) {
			Kernel.kernel.terminate();
		}
		else {
			if (ppid == 0) {
				UserKernel.removeProcess(pid);
			}
			KThread.currentThread().finish();
		}
	}

	/**
	 * Handle the exec() system call.
	 */
	private int handleExec(int fileLoc, int argc, int argvLoc) {
		if (argc < 0) {
			return -1;
		}
		String fName = readVirtualMemoryString(fileLoc, maxLen);
		if (fName == null) {
			return -1;
		}
		if (!fName.endsWith("coff")) {
			return -1;
		}
		String args[] = new String[argc];
		byte[] addrs = new byte[4];
		for (int i = 0; i < argc; i++) {
			int bytesRead = readVirtualMemory(argvLoc + i * 4, addrs);
			if (bytesRead != 4) {
				return -1;
			}
			int argAddrs = Lib.bytesToInt(addrs, 0);
			args[i] = readVirtualMemoryString(argAddrs, maxLen);
		}
		UserProcess pChild = UserProcess.newUserProcess();
		pChild.ppid = pid;
		pChildrenList.add(pChild.pid);
		boolean successful = pChild.execute(fName, args);
		return successful ? pChild.pid : -1;
	}

	/**
	 * Handle the join() system call.
	 */
	private int handleJoin(int pid, int statusLoc) {
		boolean isChild = false;
		for (int cpid : pChildrenList) {
			if (cpid == pid) {
				isChild = true;
				break;
			}
		}
		if (!isChild) {
			return -1;
		}
		UserProcess pChild = UserKernel.getProcessById(pid);
		if (pChild == null) {
			return -1;
		}

		pChild.thread.join();
		byte[] cExitStat = new byte[4];
		cExitStat = Lib.bytesFromInt(pChild.exitStat);
		UserKernel.removeProcess(pChild.pid);
		int bytesWritten = writeVirtualMemory(statusLoc, cExitStat);
		if (bytesWritten != 4) {
			return 0;
		}
		else {
			return 1;
		}
	}

	/**
	 * Handle the create() system call.
	 */
	private int handleCreate(int fileLoc) {
		String fName = readVirtualMemoryString(fileLoc, maxLen);
		OpenFile theFile = UserKernel.fileSystem.open(fName, true);
		if (theFile == null) {
			return -1;
		}
		int fileDes = getFD();
		if (fileDes == -1) {
			return -1;
		}
		oFileArray[fileDes] = new FileDescriptor(theFile, fName);
		return fileDes;
	}

	/**
	 * Handle the open() system call.
	 */
	private int handleOpen(int fileLoc) {
		String fName = readVirtualMemoryString(fileLoc, maxLen);
		OpenFile theFile = UserKernel.fileSystem.open(fName, false);
		if (theFile == null) {
			return -1;
		}
		int fileDes = getFD();
		if (fileDes == -1) {
			return -1;
		}
		oFileArray[fileDes] = new FileDescriptor(theFile, fName);
		return fileDes;
	}

	/**
	 * Handle the read() system call.
	 */
	private int handleRead(int fDes, int bufferLoc, int cnt) {
		if (fDes < 0 || fDes >= maxNumFiles || oFileArray[fDes] == null || cnt < 0 || fDes == 1) {
			return -1;
		}
		FileDescriptor fileDes = oFileArray[fDes];
		byte[] fData = new byte[cnt];
		int fdNum;

		if (fDes != 0) {
			fdNum = fileDes.openedFile.read(fileDes.pos, fData, 0, cnt);
		}else {
			fdNum = fileDes.openedFile.read(fData, 0, cnt);
		}
		if (fdNum < 0) {
			return -1;
		}
		if (fDes > 1) {
			fileDes.pos = fileDes.pos + fdNum;
		}
		writeVirtualMemory(bufferLoc, fData);
		return fdNum;
	}

	/**
	 * Handle the write() system call.
	 */
	private int handleWrite(int fDes, int bufferLoc, int cnt) {
		if (fDes < 0 || fDes >= maxNumFiles || oFileArray[fDes] == null || cnt < 0 || fDes == 0) {
			return -1;
		}
		FileDescriptor fileDes = oFileArray[fDes];
		byte[] fData = new byte[cnt];
		int bytesRead = readVirtualMemory(bufferLoc, fData);
		int fdNum;
		if (fDes != 1) {
			fdNum = fileDes.openedFile.write(fileDes.pos, fData, 0, bytesRead);
		}else {
			fdNum = fileDes.openedFile.write(fData, 0, bytesRead);
		}
		if (fdNum < 0) {
			return -1;
		}
		if (fDes > 1) {
			fileDes.pos = fileDes.pos + fdNum;
		}
		return fdNum;
	}

	/**
	 * Handle the close() system call.
	 */
	private int handleClose(int fDes) {
		if (fDes < 0 || fDes >= maxNumFiles || oFileArray[fDes] == null) {
			return -1;
		}
		FileDescriptor fileDes = oFileArray[fDes];
		boolean bool = true;
		fileDes.openedFile.close();
		if (fileDes.toDelete) {
			bool = UserKernel.fileSystem.remove(fileDes.fileName);
		}
		oFileArray[fDes] = null;
		return bool ? 0 : -1;
	}

	/**
	 * Handle the unlink() system call.
	 */
	private int handleUnlink(int fileLoc) {
		String fName = readVirtualMemoryString(fileLoc, maxLen);
		int fileDes = findFDbyName(fName);
		boolean bool = true;
		if (fileDes == -1) {
			bool = UserKernel.fileSystem.remove(fName);
		}
		else {
			oFileArray[fileDes].toDelete = true;
		}
		return bool ? 0 : -1;
	}


	private static final int
	syscallHalt = 0,
	syscallExit = 1,
	syscallExec = 2,
	syscallJoin = 3,
	syscallCreate = 4,
	syscallOpen = 5,
	syscallRead = 6,
	syscallWrite = 7,
	syscallClose = 8,
	syscallUnlink = 9;

	/**
	 * Handle a syscall exception. Called by <tt>handleException()</tt>. The
	 * <i>syscall</i> argument identifies which syscall the user executed:
	 *
	 * <table>
	 * <tr><td>syscall#</td><td>syscall prototype</td></tr>
	 * <tr><td>0</td><td><tt>void halt();</tt></td></tr>
	 * <tr><td>1</td><td><tt>void exit(int status);</tt></td></tr>
	 * <tr><td>2</td><td><tt>int  exec(char *name, int argc, char **argv);
	 * 								</tt></td></tr>
	 * <tr><td>3</td><td><tt>int  join(int pid, int *status);</tt></td></tr>
	 * <tr><td>4</td><td><tt>int  creat(char *name);</tt></td></tr>
	 * <tr><td>5</td><td><tt>int  open(char *name);</tt></td></tr>
	 * <tr><td>6</td><td><tt>int  read(int fd, char *buffer, int size);
	 *								</tt></td></tr>
	 * <tr><td>7</td><td><tt>int  write(int fd, char *buffer, int size);
	 *								</tt></td></tr>
	 * <tr><td>8</td><td><tt>int  close(int fd);</tt></td></tr>
	 * <tr><td>9</td><td><tt>int  unlink(char *name);</tt></td></tr>
	 * </table>
	 * 
	 * @param	syscall	the syscall number.
	 * @param	a0	the first syscall argument.
	 * @param	a1	the second syscall argument.
	 * @param	a2	the third syscall argument.
	 * @param	a3	the fourth syscall argument.
	 * @return	the value to be returned to the user.
	 */
	public int handleSyscall(int syscall, int a0, int a1, int a2, int a3) {
		switch (syscall) {
		case syscallHalt:
			return handleHalt();
		case syscallExit:
			handleExit(a0);
			return 0;
		case syscallExec:
			return handleExec(a0, a1, a2);
		case syscallJoin:
			return handleJoin(a0, a1);
		case syscallCreate:
			return handleCreate(a0);
		case syscallOpen:
			return handleOpen(a0);
		case syscallRead:
			return handleRead(a0, a1, a2);
		case syscallWrite:
			return handleWrite(a0, a1, a2);
		case syscallClose:
			return handleClose(a0);
		case syscallUnlink:
			return handleUnlink(a0);
		default:
			Lib.debug(dbgProcess, "Unknown syscall " + syscall);
			Lib.assertNotReached("Unknown system call!");
		}
		return 0;
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
		case Processor.exceptionSyscall:
			int result = handleSyscall(processor.readRegister(Processor.regV0),
					processor.readRegister(Processor.regA0),
					processor.readRegister(Processor.regA1),
					processor.readRegister(Processor.regA2),
					processor.readRegister(Processor.regA3)
					);
			processor.writeRegister(Processor.regV0, result);
			processor.advancePC();
			break;				       

		default:
			Lib.debug(dbgProcess, "Unexpected exception: " +
					Processor.exceptionNames[cause]);
			handleExit(1);
			Lib.assertNotReached("Unexpected exception");
		}
	}

	/**
	 * @return available file descriptor 
	 */
	private int getFD() {
		for (int i = 2; i < maxNumFiles; i++) {					//start at 2 because of stdin and stdout
			if (oFileArray[i] != null) {
				i++;
			}
			else {
				return i;
			}
		}
		return -1;
	}

	/**
	 * @return file descriptor according to file name
	 */
	private int findFDbyName(String fName) {
		for (int i = 2; i < maxNumFiles; i++) {
			if (oFileArray[i].fileName == fName) {
				return i;
			}
		}
		return -1;
	}

	/**
	 *file descriptor
	 */
	public class FileDescriptor{
		public OpenFile openedFile;
		public String fileName;
		public boolean toDelete;
		public int pos;

		public FileDescriptor(OpenFile oFile, String fName) {
			openedFile = oFile;
			fileName = fName;
			toDelete = false;
			pos = 0;
		}
	}

	/** The program being run by this process. */
	protected Coff coff;

	/** This process's page table. */
	protected TranslationEntry[] pageTable;
	/** The number of contiguous pages occupied by the program. */
	protected int numPages;

	/** The number of pages in the program's stack. */
	protected final int stackPages = 8;

	private int initialPC, initialSP;
	private int argc, argv;
	private int exitStat;
	protected int pid;											//process id
	private int ppid;											//parent process id
	private LinkedList<Integer> pChildrenList = new LinkedList<>();	//LinkedList of child processes
	private UThread thread;
	private FileDescriptor[] oFileArray;						//array of current process' open files

	private final int ROOT = 1;
	private static final int pageSize = Processor.pageSize;
	private static final char dbgProcess = 'a';
	private static final int maxNumFiles = 16;					//max number files held by a process
	private static final int maxLen = 256;						//max length of string as argument
}
