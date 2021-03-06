package psimj.network;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import psimj.Communicator;
import psimj.PrimitiveBoxer;
import psimj.Task;

/**
 * A Communicator for providing communication utilities across multiple
 * networked machines
 * 
 * @author Kyle Moy
 *
 */
public class NetworkCommunicator implements Communicator {
	public static final int PORT_RANGE_START = 8195;
	private NodeSocket[] nodes;
	private int port;
	private int rank;
	private int nprocs;

	/**
	 * Constructs a NetworkCommunicator with nodes at the specified list of
	 * addresses
	 * 
	 * @param nodeAddressList
	 *            the list of network addresses to nodes to connect
	 * @param rank
	 *            the rank of this Communicator
	 * @throws IOException
	 *             if connecting to the pool host or any node fails
	 */
	public NetworkCommunicator(List<String> nodeAddressList, int rank) throws IOException {
		this.rank = rank;
		this.port = PORT_RANGE_START + rank;

		nodes = new NodeSocket[nodeAddressList.size()];
		nprocs = nodeAddressList.size();

		// Create dummy connection with self
		nodes[rank] = new NodeSocket();

		// Initiate connections with all ranks below me
		for (int i = rank + 1; i < nodeAddressList.size(); i++) {
			nodes[i] = NodeSocket.openLater(nodeAddressList.get(i), PORT_RANGE_START + i, rank);
		}

		// Accept connections from all ranks above me
		ServerSocket serverSocket = new ServerSocket(port);
		serverSocket.setSoTimeout(500);

		// Listen for new nodes
		while (!isReady()) {
			try {
				Socket nodeSocket = serverSocket.accept();
				NodeSocket node = new NodeSocket(nodeSocket);
				int nRank = node.is.readInt();
				nodes[nRank] = node;
			} catch (IOException e) {
				// Timed out, try again
			}
		}
		serverSocket.close();
	}

	/**
	 * @return true if all nodes are connected and can communicate
	 */
	public boolean isReady() {
		for (NodeSocket node : nodes) {
			if (node == null || !node.isReady()) {
				return false;
			}
		}
		return true;
	}

	@SuppressWarnings("unchecked")
	public void runTask(Class<? extends Task> task) {
		try {
			SerializedClass cls = null;
			if (rank() == 0) {
				// Build class definition from runnable
				cls = new SerializedClass(task);

				// Broadcast class definition to all nodes
				cls = one2all_broadcast(0, cls, SerializedClass.class);

			} else {
				// Receive class definition from node 0
				cls = one2all_broadcast(0, cls, SerializedClass.class);

				// Build class from class definition
				SerializedClassLoader classLoader = new SerializedClassLoader(
						SerializedClassLoader.class.getClassLoader());
				task = classLoader.loadClass(cls);
			}
			// Instantiate class
			((Task) task.newInstance()).run(this);
		} catch (InstantiationException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		}
	}

	@Override
	public int rank() {
		return rank;
	}

	@Override
	public int nprocs() {
		return nprocs;
	}

	@Override
	public boolean topology(int i, int j) {
		// Fully connected
		return true;
	}

	@Override
	public void send(int dest, Serializable data) {
		if (!topology(rank, dest)) {
			throw new Error("Topology violation! " + rank + " cannot send to " + dest);
		}
		try {
			ObjectOutputStream o = new ObjectOutputStream(nodes[dest].os);
			o.writeObject(data);
			o.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends Serializable> T recv(int source, Class<T> type) {
		if (!topology(source, rank)) {
			throw new Error("Topology violation! " + rank + " cannot recv from " + source);
		}

		if (type.isPrimitive()) {
			type = PrimitiveBoxer.get(type);
		}

		try {
			ObjectInputStream i = new ObjectInputStream(nodes[source].is);
			Object obj = i.readObject();
			if (type.isInstance(obj)) {
				// lol type safety
				return (T) obj;
			} else {
				throw new ClassNotFoundException("Object received was not of type " + type.getName());
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends Serializable> T one2all_broadcast(int source, Serializable data, Class<T> type) {
		if (rank() == source) {
			for (int i = 0; i < nprocs(); i++) {
				if (i != rank()) {
					send(i, data);
				}
			}
		} else {
			return recv(source, type);
		}
		return (T) data;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends Serializable> List<T> all2one_collect(int dest, Serializable data, Class<T> type) {
		if (rank() != dest) {
			send(dest, data);
		} else {
			List<T> list = new ArrayList<T>();
			for (int i = 0; i < nprocs(); i++) {
				if (i != rank()) {
					list.add(recv(i, type));
				} else {
					list.add((T) data);
				}
			}
			return list;
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends Serializable> List<T> all2all_broadcast(Serializable data, Class<T> type) {
		for (int i = 0; i < nprocs(); i++) {
			if (i != rank()) {
				send(i, data);
			}
		}
		List<T> list = new ArrayList<T>();
		for (int i = 0; i < nprocs(); i++) {
			if (i != rank()) {
				list.add(recv(i, type));
			} else {
				list.add((T) data);
			}
		}
		return list;
	}

	@Override
	public void finish() {
		all2all_broadcast(0, Integer.class);
		for (NodeSocket node : nodes) {
			node.close();
		}
		nodes = null;
	}
	
	@Override
	public void close() {
		for (NodeSocket node : nodes) {
			node.close();
		}
		nodes = null;
	}
}
