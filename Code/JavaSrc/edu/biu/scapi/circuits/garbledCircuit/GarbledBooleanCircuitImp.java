/**
* %%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%
* 
* Copyright (c) 2012 - SCAPI (http://crypto.biu.ac.il/scapi)
* This file is part of the SCAPI project.
* DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
* 
* Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"),
* to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, 
* and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
* 
* The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
* 
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
* FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
* WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
* 
* We request that any publication and/or code referring to and/or based on SCAPI contain an appropriate citation to SCAPI, including a reference to
* http://crypto.biu.ac.il/SCAPI.
* 
* SCAPI uses Crypto++, Miracl, NTL and Bouncy Castle. Please see these projects for any further licensing issues.
* %%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%
* 
*/
package edu.biu.scapi.circuits.garbledCircuit;

import java.security.InvalidKeyException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.IllegalBlockSizeException;
import javax.crypto.SecretKey;

import edu.biu.scapi.circuits.circuit.BooleanCircuit;
import edu.biu.scapi.circuits.circuit.Wire;
import edu.biu.scapi.circuits.encryption.AESFixedKeyMultiKeyEncryption;
import edu.biu.scapi.exceptions.CheatAttemptException;
import edu.biu.scapi.exceptions.CiphertextTooLongException;
import edu.biu.scapi.exceptions.NoSuchPartyException;
import edu.biu.scapi.exceptions.NotAllInputsSetException;
import edu.biu.scapi.primitives.prg.PseudorandomGenerator;

/**
 * A concrete implementation of GarbledBooleanCircuit that is common for all types of circuits.<p>
 * It gets an input a object in the constructor that defines which specific type of circuit it really is.
 * 
 * @author Cryptography and Computer Security Research Group Department of Computer Science Bar-Ilan University (Moriya Farbstein)
 *
 */
public class GarbledBooleanCircuitImp implements GarbledBooleanCircuit {

	private BooleanCircuit bc;			// The Boolean circuit that this circuit should be the garbling of.
	private CircuitTypeUtil util; 		//Executes all functionalities that specific to the circuit type.
	private PseudorandomGenerator prg;  //used in case of generating the keys using a seed.
	
	private int[] outputWireIndices;	
	private ArrayList<ArrayList<Integer>> eachPartysInputWires; //Input wires' indices of each party.
	private GarbledGate[] gates; 		// The garbled gates of this garbled circuit.
	private int numberOfParties;
  	
  	/*
  	 * Holds the garbled tables of this garbled circuit. This is stored in the garbled circuit and also in the gates. 
  	 * We keep the garbled tables that way because when sending the circuit to a different party it is sufficient to send only 
  	 * the garbled tables and translation table, if needed. 
  	 * The party who receives the tables only needs to change the pointer in the holder class to the received tables.
  	 * 
  	 * We store the garbled tables in a two dimensional array, the first dimension for each gate and the other dimension for the encryptions.
  	 * Each table of each gate is a one dimensional array of bytes rather than an array of ciphertexts. 
  	 * This is for time/space efficiency reasons: If a single garbled table is an array of ciphertext that holds a byte array the space
  	 * stored by java is big. The main array holds references for each item (4 bytes). Each array in java has an overhead of 12 bytes. 
  	 * Thus the garbled table with ciphertexts has at least (12+4)*number of rows overhead.
  	 * If we store the array as one dimensional array we only have 12 bytes overhead for the entire table and thus this is the way we 
  	 * store the garbled tables. 
  	 */
	private BasicGarbledTablesHolder garbledTablesHolder;
	
	/*
	 * The translation table stores the signal bit for the output wires. Thus, it just tells you whether the wire coming out is a 
	 * 0 or 1 but nothing about the plaintext of the wires is revealed. This is good since it is possible that a circuit output 
	 * wire is also an input wire to a different gate, and thus if the translation table contained the plaintext of both possible
	 * values of the output Wire, the constructing party could change the value of the wire when it is input into a gate, and 
	 * privacy and/or correctness will not be preserved. Therefore, we only reveal the signal bit, and the other
	 * possible value for the wire is not stored on the translation table.
	 */
	private Map<Integer, Byte> translationTable;
  	
  	//A map that is used during computation to map a {@code GarbledWire}'s index to the computed and set {@code GarbledWire}.
	private Map<Integer, GarbledWire> computedWires = new HashMap<Integer,GarbledWire>();
  	
  	/**
	 * Default constructor. Sets the given boolean circuit and creates a Free XOR circuit using a AESFixedKeyMultiKeyEncryption.
	 * 
	 */
	public GarbledBooleanCircuitImp(BooleanCircuit bc){
		this(new FreeXORCircuitInput(bc, new AESFixedKeyMultiKeyEncryption()));
		
	}
	
	/**
	 * A constructor that gets an input object and creates the circuit with its contents.<p>
	 * This constructor should be used in case the garbling is done using the encryption scheme. 
	 * In case the user want to garble using a seed, use the constructor that gets a prg.<p>
	 * The created circuit will be "empty", without garbled tables. <p>
	 * After this constructor the circuit is not complete, one of the garble functions should be called in order to 
	 * fill the garbled tables and translation table.
	 * @param input Specifies which concrete type of circuit to implement.
	 */
	public GarbledBooleanCircuitImp(CircuitInput input){
		//Create an empty garbled tables.
		garbledTablesHolder = new BasicGarbledTablesHolder(new byte[input.getUngarbledCircuit().getGates().length][]);
		//Call the function that creates the gates.
		doConstruct(input);
	}
	
	/**
	 * A constructor that gets a prg and an input object and creates the circuit with their contents.<p>
	 * This constructor should be used in case the garbling is done using a seed. 
	 * In case the user want to garble using an encryption scheme, use the constructor that does not get a prg.<p>
	 * The created circuit will be "empty", without garbled tables. <p>
	 * After this constructor the circuit is not complete, one of the garble functions should be called in order to 
	 * create the underlying gates.
	 * @param input Specifies which concrete type of circuit to implement.
	 */
	public GarbledBooleanCircuitImp(CircuitInput input, PseudorandomGenerator prg){
		//Create an empty garbled tables.
		garbledTablesHolder = new BasicGarbledTablesHolder(new byte[input.getUngarbledCircuit().getGates().length][]);
		this.prg = prg;
		
		//Call the function that creates the gates.
		doConstruct(input);
	}
	
	/**
	 * Constructs a circuit from the given input.
	 * @param input Specifies which concrete type of circuit to implement.
	 */
	private void doConstruct(CircuitInput input) {
		// The input object defines which concrete circuit to use. Thus, it can create the utility class that matches this type of circuit.
		util = input.createCircuitUtil();
		
		//Extracts parameters from the given boolean circuit.
		bc = input.getUngarbledCircuit();
		outputWireIndices = bc.getOutputWireIndices();
		numberOfParties = bc.getNumberOfParties();
		eachPartysInputWires = new ArrayList<ArrayList<Integer>>();
		
		//Gets the input indices for each party.
		for (int i=1; i<=numberOfParties; i++){
			ArrayList<Integer> partyInputIndices = null;
			try {
				partyInputIndices = bc.getInputWireIndices(i);
			} catch (NoSuchPartyException e) {
				// Should not occur since the called party numbers are correct.
			}
			eachPartysInputWires.add(partyInputIndices);
			
		}
		
		//Create the circuit's gates.
		gates = util.createGates(bc.getGates(), garbledTablesHolder);
	}
	
	@Override
  	public CircuitCreationValues garble() {
		//Call the utility class to generate the keys and create the garbled tables.
		CircuitCreationValues values = util.garble(bc, garbledTablesHolder, gates);
		translationTable = values.getTranslationTable();
		return values;
	}
	
	@Override
	public CircuitCreationValues garble(byte[] seed) throws InvalidKeyException {
		if (prg == null){
			throw new IllegalStateException("This circuit can not use seed to generate keys since it has no prg. Use the other garble() function");
		}
		//Call the utility class to generate the keys and create the garbled tables.
		CircuitCreationValues values = util.garble(bc, garbledTablesHolder, gates, prg, seed);
		translationTable = values.getTranslationTable();
		return values;
	}
	
  	@Override
 	public void setGarbledInputFromUngarbledInput(Map<Integer, Byte> ungarbledInput, Map<Integer, SecretKey[]> allInputWireValues) {
  		
  		Map<Integer, GarbledWire> inputs = new HashMap<Integer, GarbledWire>();
  		Set<Integer> keys = ungarbledInput.keySet();
  		
  		//For each wireIndex, fill the map with wire index and garbled input.
  		for (Integer wireIndex : keys) {
  			inputs.put(wireIndex, new GarbledWire(allInputWireValues.get(wireIndex)[ungarbledInput.get(wireIndex)]));
  		}
  		setInputs(inputs);
  	}
  
  	@Override
  	public void setInputs(Map<Integer, GarbledWire> presetInputWires) {
  		
  		computedWires.putAll(presetInputWires);
 	}
 
  	@Override
  	public Map<Integer, GarbledWire> compute() throws NotAllInputsSetException{
  		//Check that all the inputs have been set.
  		for (int i=1; i <= getNumberOfParties(); i++){
  			List<Integer> wireNumbers = null;
			try {
				wireNumbers = getInputWireIndices(i);
			} catch (NoSuchPartyException e) {
				// Should not occur since the parties numbers are between 1 to getNumberOfParties.
			}
  			
	  		for (int wireNumber : wireNumbers){
	  			if (!computedWires.containsKey(wireNumber)) {
	  				throw new NotAllInputsSetException();
	  			}
	  		}
  		}
  		
  		/*
  		 * We use the interface GarbledGate and thus this works for all implementing classes. The compute method of the 
  		 * specific garbled gate being used will be called. This allows us to have circuits with different types of gates 
  		 * {i.e a FreeXORGarbledBooleanCircuit contains both StandardGarbledGates and FreeXORGates) and this will work for all the gates.
  		 */
  		for (GarbledGate g : gates) {
  			try {
				g.compute(computedWires);
			} catch (InvalidKeyException e) {
				// Should not occur since the keys were generated through the encryption scheme that generates keys that match it.
			} catch (IllegalBlockSizeException e) {
				// Should not occur since the keys were generated through the encryption scheme that generates keys that match it.
			} catch (CiphertextTooLongException e) {
				// Should not occur since the keys were generated through the encryption scheme that generates keys that match it.
			}
  		}
  		
  		/*
  		 * Copy only the values that we need to retain -- i.e. the values of the output wires to a new map to be returned. 
  		 * The computedWire's map contains more values than we need to retain as it has values for all wires, 
  		 * not only circuit output wires.
  		 */
  		Map<Integer, GarbledWire> garbledOutput = new HashMap<Integer, GarbledWire>();
  		for (int w : outputWireIndices) {
  			garbledOutput.put(w, computedWires.get(w));
  		}

  		return garbledOutput;
  	}	

  	@Override
  	public boolean verify(Map<Integer, SecretKey[]> allInputWireValues){
	  
  		Map<Integer, SecretKey[]> outputValues = new HashMap<Integer, SecretKey[]>();  
  		
  		//Call the internalVerify function that verifies the circuit without the last part of the translation table.
		boolean verified = internalVerify(allInputWireValues, outputValues);
		
		//Check that the output wires translate correctly. 
	    //outputValues contains both possible values for every output wire in the circuit. 
		//We check the output wire values and make sure that the 0-wire translates to a 0 and that the 1 wire translates to a 1.
  		for (int w : outputWireIndices) {
  			SecretKey zeroValue = outputValues.get(w)[0];
  			SecretKey oneValue = outputValues.get(w)[1];

  			byte signalBit = translationTable.get(w);
  			byte permutationBitOnZeroWire = (byte) ((zeroValue.getEncoded()[zeroValue.getEncoded().length - 1] & 1) == 0 ? 0 : 1);
  			byte permutationBitOnOneWire = (byte) ((oneValue.getEncoded()[oneValue.getEncoded().length - 1] & 1) == 0 ? 0 : 1);
  			byte translatedZeroValue = (byte) (signalBit ^ permutationBitOnZeroWire);
  			byte translatedOneValue = (byte) (signalBit ^ permutationBitOnOneWire);
  			if (translatedZeroValue != 0 || translatedOneValue != 1) {
  				verified = false;
  			}
  		}
  		return verified;
	}
  	
  	@Override
  	public boolean internalVerify(Map<Integer, SecretKey[]> allInputWireValues, Map<Integer, SecretKey[]> allOutputWireValues){
  	
  		/*
  		 * We will add values of non-input wires to the map as we compute them (this will take place in the Gate's 
  		 * verify method that we are about to call). In order to not change the input Map, we first copy its contents to a new Map.
  		 */
  		Map<Integer, SecretKey[]> allWireValues = new HashMap<Integer, SecretKey[]>();  
		allWireValues.putAll(allInputWireValues); 
		
		
  		// First we check that the number of gates is the same.
  		if (gates.length != bc.getGates().length) {
  			return false;
  		}
    
  		/*
  		 * Next we check gate by gate that the garbled Gate's truth table is consistent with the ungarbled gate's truth table. 
  		 * We say consistent since the gate's verify method checks the following: everywhere that the ungarbled gate's truth table 
  		 * has a 0, there is one encoding, and wherever it has a 1 there is a second encoding. Yet, under this method a 0001 truth 
  		 * table would be consistent with a 1000 truth table as we have no knowledge of what the encoded values actually translate to. 
  		 * Thus, we test for consistent and we assume that the encoded value corresponding to 0 is a 0, and that the value that 
  		 * corresponds to 1 is a 1. Based on this assumption, we map the output wire to the 0-encoded value and 1-encoded value. 
  		 * Thus if our assumption is wrong, the next gate may not verify correctly. We continue this process until we reach the circuit
  		 * output wires. At this point we confirm(or reject) all assumption by checking the translation table and seeing if the wire 
  		 * we expected to encode to a 0 was actually a 0 and the 1 was a 1. Once we have done this, we have verified the circuits are 
  		 * identical and have not relied on any unproven assumptions.
  		 */
		for (int i = 0; i < gates.length; i++) {
  			try {
				if (gates[i].verify(bc.getGates()[i], allWireValues) == false) {
					return false;
				}
			} catch (InvalidKeyException e) {
				// Should not occur since the keys were generated through the encryption scheme that generates keys that match it.
			} catch (IllegalBlockSizeException e) {
				// Should not occur since the keys were generated through the encryption scheme that generates keys that match it.
			} catch (CiphertextTooLongException e) {
				// Should not occur since the keys were generated through the encryption scheme that generates keys that match it.
			}
  		}
  		
		//Put the output keys in the given output array.
  		for (int w : outputWireIndices) {
  			allOutputWireValues.put(w, allWireValues.get(w));
  		}
  		return true;
  	}
	
	@Override
  	public Map<Integer, Wire> translate(Map<Integer, GarbledWire> garbledOutput){
  		
		Map<Integer, Wire> translatedOutput = new HashMap<Integer, Wire>();
		byte signalBit, permutationBitOnWire, value;
		
	    //Go through the output wires and translate it using the translation table.
	    for (int w : outputWireIndices) {
	    	signalBit = translationTable.get(w);
	    	permutationBitOnWire = garbledOutput.get(w).getSignalBit();
	      
	    	//Calculate the resulting value.
	    	value = (byte) (signalBit ^ permutationBitOnWire);
	    	//System.out.print(value);
	    	//Hold the result as a wire.
	    	Wire translated = new Wire(value);
	    	translatedOutput.put(w, translated);
	    }
	//System.out.println();
	    return translatedOutput;

	}
  	
	@Override
	public Map<Integer, Wire> verifiedTranslate(Map<Integer, GarbledWire> garbledOutput, Map<Integer, SecretKey[]> allOutputWireValues)
			throws CheatAttemptException {
		
		//For each wire check that the given output is one of two given possibilities.
		for (int index : getOutputWireIndices()){
			SecretKey[] keys = allOutputWireValues.get(index);
			SecretKey output = garbledOutput.get(index).getValueAndSignalBit();
			
			if (!(equalKey(output, keys[0])) && !(equalKey(output, keys[1]))){
				throw new CheatAttemptException("The given output value is not one of the two given possible values");
			}
		}
		
		//After verified, the output can be translated.
		return translate(garbledOutput);
		
	}
	
	/**
	 * Check that the given keys are the same.
	 * @param output The first key to compare.
	 * @param key The second key to compare.
	 * @return true if both keys are the same; False otherwise.
	 */
	private boolean equalKey(SecretKey output, SecretKey key){
		byte[] outputBytes = output.getEncoded();
		byte[] keyBytes = key.getEncoded();
		
		//Compare the keys' lengths.
		if (outputBytes.length != keyBytes.length){
			return false;
		}
		
		int length = outputBytes.length;
		
		//Compare the keys' contents.	
		for (int i=0; i<length; i++){
			if (outputBytes[i] != keyBytes[i]){
				return false;
			}
		}
		return true;
	}
	
	@Override
	public List<Integer> getInputWireIndices(int partyNumber) throws NoSuchPartyException {
		if (partyNumber>numberOfParties){
  			throw new NoSuchPartyException();
  		}
		return eachPartysInputWires.get(partyNumber-1);
	}

	@Override
	public int getNumberOfInputs(int partyNumber) throws NoSuchPartyException {
		if (partyNumber>numberOfParties){
  			throw new NoSuchPartyException();
  		}
		return eachPartysInputWires.get(partyNumber-1).size();
	}
  
	@Override
	public GarbledTablesHolder getGarbledTables(){
	  
		return garbledTablesHolder;
	}
  
	@Override
	public void setGarbledTables(GarbledTablesHolder garbledTables){
		if (!(garbledTables instanceof BasicGarbledTablesHolder)){
			throw new IllegalArgumentException("garbledTables should be an instance of BasicGarbledTablesHolder");
		}
		this.garbledTablesHolder = (BasicGarbledTablesHolder) garbledTables;
	}
	
	@Override
	public Map<Integer, Byte> getTranslationTable(){
	  
		return translationTable;
	}
  
	@Override
	public void setTranslationTable(Map<Integer, Byte> translationTable){
		
		this.translationTable = translationTable;
	}

	@Override
	public int[] getOutputWireIndices() {
		return outputWireIndices;
	}

	@Override
	public int getNumberOfParties() {
		return numberOfParties;
	}
	
	public int getNumberOfGates(){
		return gates.length;
	}
}
