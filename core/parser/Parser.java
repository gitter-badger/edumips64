/*
 * Parser.java
 *
 * Parses a MIPS64 source code and fills the symbol table and the memory.
 *
 * (c) 2008 Andrea Spadaccini
 *
 * This file is part of the EduMIPS64 project, and is released under the GNU
 * General Public License.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edumips64.core.parser;

import edumips64.utils.IrregularStringOfBitsException;
import edumips64.core.*;
import edumips64.core.is.Instruction;
import edumips64.core.parser.tokens.*;
import java.util.*;
import java.io.*;

public class Parser {
    // Association between directives and parsing algorithms (Strategy design
    // pattern)
    protected HashMap<String, ParsingAlgorithm> algorithms;

    // This flag is set to true when we switch parsing algorithm.
    // If in the end it's set to false, it means that we didn't do any real
    // parsing.
    protected boolean didSomeRealParsing = false;

    // List of parsing errors
    protected List<ParserException> exceptions;
    protected boolean hasOnlyWarnings = true;   // Set to false if the parser encounters errors

    // Stupid inner class, because we are using a stupid programming language
    // And no, I'm not going to write getters and setters.
    protected class InstructionData {
        public InstructionData(Instruction instr, List<Token> params, int address, Token token) {
            this.instr = instr;
            this.params = params;
            this.address = address;
            this.token = token;
        }
        public Instruction instr;
        public Token token;
        public int address;
        public List<Token> params;
    }

    // List of instructions with parameters
    protected List<InstructionData> instructions;

    // Set of deprecated instructions names
    protected Set<String> deprecateInstruction;
    protected static Parser instance;
    protected Scanner scanner;
    protected ParsingAlgorithm default_alg;
    protected SymbolTable symbols;
    protected Memory memory;
    protected String path;
    /* --------------------
     * Public methods
     * --------------------
     */

    public void reset() {
        edumips64.Main.logger.debug("Resetting parser");
        instructions = new LinkedList<InstructionData>();
        exceptions = new LinkedList<ParserException>();
        hasOnlyWarnings = true;
    }


	public void parse(String filename) throws FileNotFoundException, SecurityException, IOException,ParserErrorsException {
        edumips64.Main.logger.debug("Parse begins!");

        File inputFile = new File(filename);
        if(! inputFile.exists())
            throw new FileNotFoundException(filename);

        String absolutePath = inputFile.getAbsolutePath();

        int separator = inputFile.getAbsolutePath().lastIndexOf(File.separator);

        edumips64.Main.logger.debug("Absolute path " + absolutePath);
        edumips64.Main.logger.debug("Separator: " + separator);

        if(separator != -1)
            this.path = absolutePath.substring(0,separator);
        else
            this.path = "";

        edumips64.Main.logger.debug("Filename: " + filename);
        edumips64.Main.logger.debug("Path: " + path);

        BufferedReader reader = new BufferedReader(preProcess(absolutePath));
        scanner = new Scanner(reader);
        edumips64.Main.logger.debug("Scanner created, starting default parsing Algorithm");
        default_alg.parse(scanner);

        // Checking if HALT instruction is present
        // and adding it if it's not
        checkInstructions();
            
        Token currentToken = null; // used for error reporting

        // Packing instruction
        edumips64.Main.logger.debug("Will now pack the instructions");
        for(InstructionData i : instructions) {
            edumips64.Main.logger.debug("Processing " + i.instr.getFullName());
            try {
                for(Token t : i.params) {
                    currentToken = t;
                    edumips64.Main.logger.debug("Adding " + t + " to parameters list");
                    t.addToParametersList(i.instr);
                }

                currentToken = i.token;
                i.instr.pack();
                edumips64.Main.logger.debug("Instruction packed, adding to memory");
                memory.addInstruction(i.instr, i.address);
            }
            catch(ParameterException e) {
                // TODO: correzione addError
                addError(currentToken, e.getMessage());
            }
            catch (SymbolTableOverflowException e) {
                addError(currentToken, "PARSER_OUT_OF_BOUNDS");
            }
            catch (IrregularStringOfBitsException e) {
                addError(currentToken, "PARSER_UNKNOWN_ERROR");
            }
        }

        if(!didSomeRealParsing) {
            addError(new ErrorToken(""), "PARSER_NO_DATA_OR_CODE_DIRECTIVE");
        }

        // Throw exception if needed
        if(exceptions.size() > 0)
            throw new ParserErrorsException(exceptions, hasOnlyWarnings);
    }

    // Singleton design pattern
    public static Parser getInstance() {
        if(instance == null)
            instance = new Parser();
        return instance;
    };
    /* ----------------------------
     * Package-wide visible methods
     * used by parsing algorithms
     * ----------------------------
     */
    boolean hasAlgorithm(String directive) {
        return algorithms.containsKey(directive);
    }

    void switchParsingAlgorithm(String directive) { 
        edumips64.Main.logger.debug("Switching parser due to directive " + directive);
        didSomeRealParsing = true;
        algorithms.get(directive).parse(scanner);
    }

    void addError(Token t, String error) {
        edumips64.Main.logger.debug("ERR ************* " + error + ": " + t);
        System.out.println("ERR ************* " + error + ": " + t);
        hasOnlyWarnings = false;
        exceptions.add(new ParserException(error, t.getLine(), t.getColumn(), t.toString(), true));
    }

    void addWarning(Token t, String error) {
        edumips64.Main.logger.debug("WARN ************* " + error + ": " + t);
        System.out.println("WARN ************* " + error + ": " + t);
        exceptions.add(new ParserException(error, t.getLine(), t.getColumn(), t.toString(), false));
    }

    void addInstruction(Instruction instr, int address, List<Token> params, String label, Token instrToken) {
        edumips64.Main.logger.debug("Adding " + instr + " to SymbolTable, label " + label + ", address " + address);
        if(label != null) {
            try {
                symbols.setInstructionLabel(address, label);
                instr.setLabel(label);
                edumips64.Main.logger.debug("ADDED LABEL " + label);
            }
            catch (SameLabelsException e) {
                addError(instrToken, "PARSER_DUPLICATE_LABEL");
            }
        }
        // For later parameters processing
        instr.setAddress(address);
        instructions.add(new InstructionData(instr, params, address, instrToken));
    }

    void addMemoryAddressToSymbolTable(int address, Token label) throws MemoryElementNotFoundException{
        edumips64.Main.logger.debug("Adding " + label.getBuffer() + " to SymbolTable, address " + address);
        try {
            symbols.setCellLabel(address, label.getBuffer());
        }
        catch (SameLabelsException e) {
            addError(label, "PARSER_DUPLICATE_LABEL");
        }
    }


    static boolean isInstruction(Token t) {
        return Instruction.buildInstruction(t.getBuffer()) != null;
    }

    String getLastCodeLine() {
        return scanner.getLastCodeLine();
    }

    /* -----------------
     * Protected methods
     * -----------------
     */ 

    protected void checkInstructions(){
        boolean halt = false;

        for(InstructionData i : instructions){
            String instrName = i.token.getBuffer();

            if(deprecateInstruction.contains(instrName))
                addWarning(i.token, "WINMIPS64_NOT_MIPS64");

            if(instrName.equalsIgnoreCase("HALT") || 
                    (instrName.equalsIgnoreCase("SYSCALL") && i.params.get(0).getBuffer().equals("0"))){
                halt = true;
            }
        }

        if(!halt){
            int haltAddress = 0;
            if(instructions.size() > 0) {
                haltAddress = instructions.get(instructions.size()-1).address + 4;
            }
            edumips64.Main.logger.debug("haltAddress = " + haltAddress);
            addWarning(new ErrorToken("HALT"), "HALT_NOT_PRESENT");
            edumips64.Main.logger.debug("haltAddress = " + haltAddress);

            Instruction tmpInst = Instruction.buildInstruction("SYSCALL");
            tmpInst.setFullName("SYSCALL 0");
            Token instrName = new IdToken("SYSCALL");
            String label = null;
            List<Token> paramsList = new ArrayList<Token>(1);
            paramsList.add(new IntegerToken("0"));

            addInstruction(tmpInst, haltAddress, paramsList,label, instrName);
        }
    }

	protected String readLines(Reader stream) throws IOException
	{
		String ret = "";
		String line;
        BufferedReader in = new BufferedReader(stream);

		while ((line = in.readLine()) != null)
		    ret += line + "\n";

		return ret;
	}

    protected String processInclude(String filename, Set<String> alreadyIncluded) throws IOException{
        BufferedReader reader;
        String code;

        File inputFile = new File(filename);

        if(!inputFile.isAbsolute())
            filename = this.path + File.separator + filename;

        inputFile = new File(filename);
        if(!inputFile.exists())
            throw new FileNotFoundException(filename);

        if(alreadyIncluded.contains(filename)){
            edumips64.Main.logger.debug("file " + filename + " already included");
            addError(new ErrorToken("#include"), "PARSER_WRONG_INCLUDE");
            return new String("");
        }

        alreadyIncluded.add(filename);

        reader = new BufferedReader(new InputStreamReader(new FileInputStream(filename),"ISO-8859-1"));

        code = readLines(reader);
        int i = 0;
        int j,k;

        while(true){
            System.out.println(code);
            i = code.indexOf("#include", i);
            k = code.lastIndexOf("\n", i);
            j = code.lastIndexOf(";", i); //potrebbe esserci un commento!
            System.out.println("\n" + k);
            System.out.println(j);
            System.out.println(i);

            if(i == -1)
                break;

            if( j > 0 && k > 0  && (j > k && j < i)){
                System.out.println("Continuo");
                i++;
                continue;
            }

            int end = code.indexOf("\n", i);
            if(end == -1)
                end = code.length();

            String includedFileName = code.substring(i+9, end).split(";")[0].trim();
            code = code.substring(0,i) + processInclude(includedFileName,alreadyIncluded) + code.substring(end);
            i++;
        }
        
        return code;
    }

    protected Reader preProcess(String fileName) throws IOException{
        Set<String> included = new HashSet<String>();
        String code = processInclude(fileName,included);
        return new StringReader(code);
    }

    protected Parser() {
        algorithms = new HashMap<String, ParsingAlgorithm>();
        default_alg = new NullParsingAlgorithm(this);
        CodeParsingAlgorithm code_pa = new CodeParsingAlgorithm(this);
        DataParsingAlgorithm data_pa = new DataParsingAlgorithm(this);
        symbols = SymbolTable.getInstance();
        memory = Memory.getInstance();
        instructions = new LinkedList<InstructionData>();
        exceptions = new LinkedList<ParserException>();

        // Association of parsing algorithms with directives
        registerAlgorithm(".DATA", data_pa);
        registerAlgorithm(".CODE", code_pa);
        registerAlgorithm(".TEXT", code_pa);

	    deprecateInstruction = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        deprecateInstruction.add("BNEZ");
        deprecateInstruction.add("BEQZ");
        deprecateInstruction.add("HALT");
        deprecateInstruction.add("DADDUI");
        deprecateInstruction.add("L.D");
        deprecateInstruction.add("S.D");
    }

    protected void registerAlgorithm(String directive, ParsingAlgorithm p) {
        edumips64.Main.logger.debug("Registering a parser for directive " + directive + ", " + p.toString());
        algorithms.put(directive, p);
    }

}