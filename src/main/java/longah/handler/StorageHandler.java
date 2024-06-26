package longah.handler;

import java.util.ArrayList;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.Scanner;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;

import longah.node.Member;
import longah.util.MemberList;
import longah.util.Subtransaction;
import longah.node.Transaction;
import longah.util.TransactionList;
import longah.exception.LongAhException;
import longah.exception.ExceptionMessage;

/*
 * Storage Format
 * -----------
 * Members:
 * [Name]SEP[Balance]
 * 
 * Transactions:
 * [Lender]SEP[Borrower1]SEP[Value]SEP...
 */
public class StorageHandler {
    // Constants
    private static final double EPSILON = 1e-3; // Double Comparison Epsilon

    // ASCII Defined Separator
    private static final String SEPARATOR = String.valueOf(Character.toChars(31));
    private static final String MEMBERS_FILE_STRING = "members.txt";
    private static final String TRANSACTIONS_FILE_STRING = "transactions.txt";

    // Storage Directory Constants
    private String storageFolderPath = "./data";
    private String storageMembersFilePath;
    private String storageTransactionsFilePath;
    private File membersFile;
    private File transactionsFile;

    // Objects for Storate
    private MemberList members;
    private TransactionList transactions;
    private Scanner[] scanners = new Scanner[2];

    /**
     * Initializes a new StorageHandler instance.
     * Each instance handles the data storage requirements of each group of members.
     * 
     * @throws LongAhException If the data files are not created
     */
    public StorageHandler(MemberList members, TransactionList transactions, String groupName)
            throws LongAhException {
        // Create data directory if it does not exist
        initDir();
        this.storageFolderPath += "/" + groupName;
        // Create group directory if it does not exist
        if(!new File(this.storageFolderPath).exists()) {
            new File(this.storageFolderPath).mkdir();
        }

        // Create data files if they do not exist
        this.storageMembersFilePath = this.storageFolderPath + "/" + MEMBERS_FILE_STRING;
        this.storageTransactionsFilePath = this.storageFolderPath + "/" + TRANSACTIONS_FILE_STRING;
        this.membersFile = new File(this.storageMembersFilePath);
        this.transactionsFile = new File(this.storageTransactionsFilePath);

        try {
            membersFile.createNewFile();
            transactionsFile.createNewFile();
        } catch (IOException e) {
            throw new LongAhException(ExceptionMessage.STORAGE_FILE_NOT_CREATED);
        }

        this.members = members;
        this.transactions = transactions;
        initStorageScanners();

        // Load data from data files into MemberList and TransactionList objects
        loadAllData();
        Logging.logInfo("Data loaded from storage.");
    }

    /**
     * Initializes the storage scanner to read data files.
     * 
     * @throws LongAhException If the data files are not found
     */
    public void initStorageScanners() throws LongAhException {
        try {
            this.scanners[0] = new Scanner(this.membersFile);
            this.scanners[1] = new Scanner(this.transactionsFile);
        } catch (FileNotFoundException e) {
            throw new LongAhException(ExceptionMessage.STORAGE_FILE_NOT_FOUND);
        }
    }

    public static void initDir() {
        File f = new File("./data");
        if (!f.exists()) {
            f.mkdir();
        }
    }

    /**
     * Loads the members data from the data file into the MemberList object.
     * 
     * @throws LongAhException If the data file is not read or the content is invalid
     */
    public void loadMembersData() throws LongAhException {
        Scanner sc = this.scanners[0];
        while (sc.hasNextLine()) {
            try {
                String data = sc.nextLine();
                if (data.equals("")) {
                    continue;
                }

                String[] memberData = data.split(SEPARATOR);
                assert memberData.length == 2 : "Member data should have 2 parts.";

                String name = memberData[0];
                double balance = Double.parseDouble(memberData[1]);
                this.members.addMember(name, balance);
            } catch (LongAhException | NumberFormatException e) {
                throw new LongAhException(ExceptionMessage.INVALID_STORAGE_CONTENT);
            } 
        }
    }

    /**
     * Loads the transactions data from the data file into the TransactionList object.
     * 
     * @throws LongAhException If the data file is not read or the content is invalid
     */
    public void loadTransactionsData() throws LongAhException {
        Scanner sc = this.scanners[1];
        boolean isError = false;
        while (sc.hasNextLine()) {
            try {
                String data = sc.nextLine();
                if (data.equals("")) {
                    continue;
                }

                String[] transactionData = data.split(SEPARATOR);
                String lenderName = transactionData[0];
                String transactionTime = null;
                Member lender = members.getMember(lenderName);
                Transaction transaction;
                ArrayList<Subtransaction> subtransactions = new ArrayList<>();
                int startOfSubtransactions = 1;

                if (transactionData[1].contains("-")) {
                    transactionTime = transactionData[1];
                    startOfSubtransactions = 2;
                }

                for (int i = startOfSubtransactions; i < transactionData.length; i += 2) {
                    try {
                        Subtransaction subtransaction = parseSubtransaction(transactionData[i],
                                transactionData[i + 1], lender, members);
                        subtransactions.add(subtransaction);
                    } catch (LongAhException e) {
                        // Skip the subtransaction if it is invalid
                        isError = true;
                        continue;
                    }
                }

                if (startOfSubtransactions == 1) {
                    transaction = new Transaction(lender, subtransactions, members);
                } else {
                    transactionTime = transactionData[1];
                    transaction = new Transaction(lender, subtransactions, members, transactionTime);
                }
                this.transactions.addTransaction(transaction);

            } catch (LongAhException | NumberFormatException e) {
                throw new LongAhException(ExceptionMessage.INVALID_STORAGE_CONTENT);
            }
        }

        boolean checksum = checkTransactions(members);
        if (!checksum) {
            throw new LongAhException(ExceptionMessage.STORAGE_FILE_CORRUPTED);
        }
        if (isError) {
            UI.showMessage("Some transactions are invalid and have been skipped.");
        }
    }

    /**
     * Parses the subtransaction data from the data file into a Subtransaction object.
     * 
     * @param borrowerName The name of the borrower in the subtransaction
     * @param value The amount borrowed in the subtransaction
     * @param lender The lender in the subtransaction
     * @param members The MemberList object to reference the members in the subtransaction
     * @return The Subtransaction object parsed from the data file
     * @throws LongAhException If the data file is not read or the content is invalid
     */
    public static Subtransaction parseSubtransaction(String borrowerName, String value,
            Member lender, MemberList members) throws LongAhException{
        try {
            Member borrower = members.getMember(borrowerName);
            double amount = Double.parseDouble(value);

            if (borrower.equals(lender)) {
                throw new LongAhException(ExceptionMessage.INVALID_TRANSACTION_FORMAT);
            }
            // Exception is thrown if the amount borrowed has more than 2dp
            if (BigDecimal.valueOf(amount).scale() > 2) {
                throw new LongAhException(ExceptionMessage.INVALID_TRANSACTION_VALUE);
            }
            // Exception is thrown if the amount borrowed is not positive
            if (amount <= 0) {
                throw new LongAhException(ExceptionMessage.INVALID_TRANSACTION_VALUE);
            }

            return new Subtransaction(lender, borrower, amount);
          
        } catch (NumberFormatException | LongAhException e) {
            throw new LongAhException(ExceptionMessage.INVALID_STORAGE_CONTENT);
        }
    }

    /**
     * Returns if the total balance of all members in the MemberList object is 0.
     * 
     * @param members The MemberList object to check the total balance from
     * @return If the total balance is 0, return true. Otherwise, return false.
     */
    public boolean checkTransactions(MemberList members) {
        if (members.getMemberListSize() == 0) {
            return true;
        }
        double total = 0.0;
        for (Member member : members.getMembers()) {
            total += member.getBalance();
        }
        if (Math.abs(total) < EPSILON) {
            return true;
        }
        return false;
    }

    /**
     * Loads all data from the data files into the MemberList and TransactionList objects.
     * 
     * @throws LongAhException If the data files are not read or the content is invalid
     */
    public void loadAllData() throws LongAhException {
        loadMembersData();
        loadTransactionsData();

        // Close the scanners after reading the data
        this.scanners[0].close();
        this.scanners[1].close();
    }

    /**
     * Saves the members data from the MemberList object into the data file.
     * 
     * @throws LongAhException If the data file is not written
     */
    public void saveMembersData() throws LongAhException {
        try {
            FileWriter fw = new FileWriter(this.membersFile);
            for (Member member : this.members.getMembers()) {
                String data = member.toStorageString(SEPARATOR);
                fw.write(data + "\n");
            }
            fw.close();
        } catch (IOException e) {
            throw new LongAhException(ExceptionMessage.STORAGE_FILE_NOT_WRITTEN);
        }
    }

    /**
     * Saves the transactions data from the TransactionList object into the data file.
     * 
     * @throws LongAhException If the data file is not written
     */
    public void saveTransactionsData() throws LongAhException {
        try {
            FileWriter fw = new FileWriter(this.transactionsFile);
            for (Transaction transaction : this.transactions.getTransactions()) {
                String data = transaction.toStorageString(SEPARATOR);
                fw.write(data + "\n");
            }
            fw.close();
        } catch (IOException e) {
            throw new LongAhException(ExceptionMessage.STORAGE_FILE_NOT_WRITTEN);
        }
    }

    /**
     * Saves all data from the MemberList and TransactionList objects into the data files.
     * 
     * @throws LongAhException If the data files are not written
     */
    public void saveAllData() throws LongAhException {
        saveMembersData();
        saveTransactionsData();
    }

    /**
     * Helper method to remove a directory and its contents.
     * 
     * @param dir The file to be removed
     */
    public static void deleteDir(File dir) {
        File[] contents = dir.listFiles();
        if (contents != null) {
            for (File file : contents) {
                deleteDir(file);
            }
        }
        dir.delete();
    }
}
