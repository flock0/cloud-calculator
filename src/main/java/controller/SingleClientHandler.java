package controller;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

import util.Config;
import util.FixedParameters;
import util.HMACUtils;
import util.SecureChannelSetup;
import util.TamperedException;
import channels.Channel;
import channels.ChannelSet;
import channels.ClientCommunicator;
import channels.ComputationCommunicator;
import channels.ComputeHMACChannel;
import channels.TcpChannel;
import computation.ComputationResult;
import computation.NodeRequest;
import computation.Result;
import computation.TamperedResult;

public class SingleClientHandler implements Runnable {

	private Config config;
	private ConcurrentHashMap<Character, ConcurrentSkipListSet<Node>> activeNodes;
	private ConcurrentHashMap<String, User> users;
	private ClientCommunicator communicator;
	private User currentUser = null;
	private ComputationCommunicator currentComputationCommunicator = null;
	private boolean sessionIsBeingTerminated = false;
	private boolean successfullyInitialized = false;
	private Channel underlyingChannel; // The original underlying channel
	private ChannelSet openChannels;
	private HashMap<Character, Long> statistic;
	private PrivateKey controllerPrivateKey;
	private HMACUtils hmacUtils;

	public SingleClientHandler(
			Channel channel,
			ConcurrentHashMap<Character, ConcurrentSkipListSet<Node>> activeNodes,
			ConcurrentHashMap<String, User> users, PrivateKey controllerPrivateKey, ChannelSet openChannels, Config config,
			HMACUtils hmacUtils, HashMap<Character, Long> statistic) {
		this.config = config;
		this.activeNodes = activeNodes;
		this.users = users;
		this.controllerPrivateKey = controllerPrivateKey;
		this.openChannels = openChannels;
		this.underlyingChannel = channel;
		this.statistic = statistic;
		this.hmacUtils = hmacUtils;
		
		try {
			authenticateClient();
		} catch(IOException e) {
			channel.close();
		}
	}
	
	private void authenticateClient() throws IOException {
		SecureChannelSetup auth = new SecureChannelSetup(underlyingChannel, controllerPrivateKey, config);
		Channel aesChannel = auth.awaitAuthentication();
		this.communicator = new ClientCommunicator(aesChannel);
		currentUser = users.get(auth.getAuthenticatedUsername());
		currentUser.increaseOnlineCounter();
		successfullyInitialized = true;
	}

	@Override
	public void run() {
		/*
		 * Only read commands via the encrypted channel when
		 * we are successfully initialized and have a user
		 * currently authenticated.
		 */
		if(successfullyInitialized && isLoggedIn()) {
			try {
				while (true) {
					ClientRequest request = communicator.getRequest();
	
					switch (request.getType()) {
					case Login:
						communicator.sendAnswer(handleLogin(request));
						break;
					case Logout:
						communicator.sendAnswer(handleLogout());
						authenticateClient();
						break;
					case Credits:
						communicator.sendAnswer(handleCredits());
						break;
					case Buy:
						communicator.sendAnswer(handleBuy(request));
						break;
					case List:
						communicator.sendAnswer(handleList());
						break;
					case Compute:
						communicator.sendAnswer(handleCompute(request));
						break;
					default:
						break; // Skip invalid requests
					}
				}
			} catch (SocketException e) {
				sessionIsBeingTerminated = true;
				System.out.println("Socket to client closed: " + e.getMessage());
			} catch (IOException e) {
				System.out.println("Error on getting request: " + e.getMessage());
			} finally {
				logoutAndClose();
			}
		}
	}

	private String handleLogin(ClientRequest request) {
		if (isLoggedIn())
			return "You are already logged in!";
		if (!credentialsAreValid(request))
			return "Wrong username or password.";

		currentUser = users.get(request.getUsername());
		currentUser.increaseOnlineCounter();
		return "Successfully logged in.";
	}

	private boolean isLoggedIn() {
		return currentUser != null;
	}

	private boolean credentialsAreValid(ClientRequest request) {
		return users.containsKey(request.getUsername())
				&& users.get(request.getUsername()).isCorrectPassword(
						request.getPassword());
	}

	private String handleLogout() {
		currentUser.decreaseOnlineCounter();
		currentUser = null;
		return "Successfully logged out.";
	}

	private String handleCredits() {
		if (!isLoggedIn())
			return "You need to log in first.";
		return String.format("You have %d credits left.", currentUser.getCredits());

	}

	private String handleBuy(ClientRequest request) {
		if (!isLoggedIn())
			return "You need to log in first.";
		if(request.getBuyAmount() <= 0)
			return "Error: Amount must be positive.";

		currentUser.setCredits(currentUser.getCredits()
				+ request.getBuyAmount());
		return String.format("You now have %d credits.",
				currentUser.getCredits());
	}

	private String handleList() {
		if (!isLoggedIn())
			return "You need to log in first.";
		String availableOperators = getAvailableOperators();
		if(availableOperators.isEmpty())
			return " "; // Avoid issues that occur when AES-decrypting empty strings
		else
			return getAvailableOperators();
	}

	private String getAvailableOperators() {
		StringBuilder builder = new StringBuilder();
		// Sync'ed, so no inconsistent list can be returned 
		synchronized(activeNodes) {
			for (Character operator : activeNodes.keySet())
				if (!activeNodes.get(operator).isEmpty())
					builder.append(operator);
		}
		return builder.toString();
	}

	private String handleCompute(ClientRequest request) throws IOException {
		
		//// Prerequisites ////
		if (!isLoggedIn())
			return "You need to log in first.";
		if (!currentUser.hasEnoughCredits(request))
			return "Not enough credits!";
		
		char[] operators = request.getOperators();		
		addToStatistic(operators);
		
		if (!canBeComputed(request))
			return "Error: Operators unsupported!";

		//// Declarations ////
		int[] operands = request.getOperands();
		final int totalOperatorCount = operators.length;
		final int totalOperandCount = operands.length;
		int remainingOperationsCount = totalOperatorCount;
		int firstOperand = operands[0];
		int secondOperand;
		char nextOperator;

		try {
			while (remainingOperationsCount != 0) {

				//// Get Next Request ////
				nextOperator = operators[totalOperatorCount - remainingOperationsCount]; 
				secondOperand = operands[totalOperandCount - remainingOperationsCount];
				NodeRequest computationRequest = new NodeRequest(firstOperand,
						nextOperator, 
						secondOperand);

				boolean foundAvailableNode = false;
				Iterator<Node> orderedNodesForNextOperator = activeNodes.get(nextOperator).iterator();

				while(!foundAvailableNode && orderedNodesForNextOperator.hasNext()) {

					//// Find Node for next request ////
					Node nextNodeToTry = orderedNodesForNextOperator.next();
					if(nextNodeToTry.isOnline()) {
						currentComputationCommunicator = null;
						try {
							Channel channelForCommunicator = new ComputeHMACChannel(
																new TcpChannel(
																	new Socket(nextNodeToTry.getIPAddress(), nextNodeToTry.getTCPPort())),
																hmacUtils); 
							openChannels.add(channelForCommunicator);
							currentComputationCommunicator = new ComputationCommunicator(channelForCommunicator);
							
							currentComputationCommunicator.requestComputation(computationRequest);

							Result r = currentComputationCommunicator.getResult();
							
							if(r instanceof ComputationResult) // Otherwise just skip this node for now and try another one
							{			
								ComputationResult result = (ComputationResult)r;
								//// Check Result ////
								switch(result.getStatus()) {
								case OK:
									foundAvailableNode = true;
									firstOperand = result.getNumber();
									remainingOperationsCount--;
	
									updateUsageStatistics(nextNodeToTry, result);
									break;
								case DivisionByZero:
									deductCredits(totalOperatorCount - remainingOperationsCount + 1);
									return "Error: division by 0";
								case OperatorNotSupported:
									break; // Just skip this node for now and try another one
								default:
									break; // Just skip this node for now and try another one
								}
							} else if (r instanceof TamperedResult) {
								System.out.println("A node identified a tampered message.");
								return "A node identified a tampered message. No credits have been deducted for the computation.";
							}
						} catch (SocketException e) {
							// Just skip this node for now and try another one
						} finally {
							if(currentComputationCommunicator != null)
								currentComputationCommunicator.close();
						}

					}
				}
				if(!foundAvailableNode)
					return "Error: Nodes crashed!";
			}

			deductCredits(totalOperatorCount);
			return String.valueOf(firstOperand);

		} catch (TamperedException e) {
			System.out.println(e.getMessage());
			return e.getMessage() + "No credits have been deducted for the computation.";
		} catch (IOException e) {
			System.out.println("Error on getting result: " + e.getMessage());
			if(!sessionIsBeingTerminated)
				return "Error: An internal error occured";
			throw e;
		} finally {
			if(currentComputationCommunicator != null)
				currentComputationCommunicator.close();
		}
	}

	private void addToStatistic(char[] op)
	{
		synchronized(statistic)
		{
			for(Character c: op)
			{
				Long value = 0L;
				if(statistic.containsKey(c))
				{
					value = statistic.get(c);
				}			
				value += 1;
				statistic.put(c, value);
			}
		}
	}

	private boolean canBeComputed(ClientRequest request) {
		String availableOperators = getAvailableOperators();
		char[] requestOperators = request.getOperators();

		for (int i = 0; i < requestOperators.length; i++)
			if (availableOperators.indexOf(requestOperators[i]) == -1)
				return false;

		return true;
	}

	private void updateUsageStatistics(Node node, ComputationResult result) {
		int usageCost = calculateUsageCost(result);
		synchronized(node) {
			synchronized(activeNodes) {
				// Remove Node from all Operator Sets, change the Usage value and re-add.
				List<ConcurrentSkipListSet<Node>> setsWithNodes = new ArrayList<>();
				for(ConcurrentSkipListSet<Node> operatorSet : activeNodes.values()) {
					if(operatorSet.contains(node))
						setsWithNodes.add(operatorSet);
					
					operatorSet.remove(node);
				}
				node.setUsage(node.getUsage() + usageCost);
				for(ConcurrentSkipListSet<Node> operatorSet : setsWithNodes)
					operatorSet.add(node);
			}
		}

	}

	private int calculateUsageCost(ComputationResult result) {
		int abs = Math.abs(result.getNumber());
		if(abs == 0)
			abs++;

		int digits = (int)(Math.log10(abs)+1);
		return digits * FixedParameters.USAGE_COST_PER_RESULT_DIGIT;
	}

	private void deductCredits(int operatorCount) {
		currentUser.setCredits(currentUser.getCredits() - FixedParameters.CREDIT_COST_PER_OPERATOR * operatorCount);

	}

	private void logoutAndClose() {
		if(currentUser != null) {
			currentUser.decreaseOnlineCounter();
			currentUser = null;
		}
		communicator.close();
	}

}
