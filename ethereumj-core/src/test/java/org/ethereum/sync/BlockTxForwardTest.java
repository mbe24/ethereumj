package org.ethereum.sync;

import com.typesafe.config.ConfigFactory;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.ethereum.config.SystemProperties;
import org.ethereum.core.Block;
import org.ethereum.core.BlockSummary;
import org.ethereum.core.Transaction;
import org.ethereum.core.TransactionReceipt;
import org.ethereum.crypto.ECKey;
import org.ethereum.facade.Ethereum;
import org.ethereum.facade.EthereumFactory;
import org.ethereum.listener.EthereumListener;
import org.ethereum.listener.EthereumListenerAdapter;
import org.ethereum.mine.Ethash;
import org.ethereum.mine.MinerListener;
import org.ethereum.net.eth.message.EthMessage;
import org.ethereum.net.eth.message.EthMessageCodes;
import org.ethereum.net.eth.message.NewBlockHashesMessage;
import org.ethereum.net.eth.message.StatusMessage;
import org.ethereum.net.eth.message.TransactionsMessage;
import org.ethereum.net.message.Message;
import org.ethereum.net.rlpx.Node;
import org.ethereum.net.server.Channel;
import org.ethereum.util.ByteUtil;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;

import javax.annotation.PostConstruct;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Long running test
 *
 * 3 peers: A <-> B <-> C where A is miner, C is issuing txs, and B should forward Txs/Blocks
 */
@Ignore
public class BlockTxForwardTest {

    static final Logger testLogger = LoggerFactory.getLogger("TestLogger");

    public BlockTxForwardTest() {
        LogManager.getRootLogger().setLevel(Level.INFO);
    }

    private static class BasicSample implements Runnable {
        static final Logger sLogger = LoggerFactory.getLogger("sample");

        private String loggerName;
        public Logger logger;

        @Autowired
        protected Ethereum ethereum;

        @Autowired
        protected SystemProperties config;

        // Spring config class which add this sample class as a bean to the components collections
        // and make it possible for autowiring other components
        private static class Config {
            @Bean
            public BasicSample basicSample() {
                return new BasicSample();
            }
        }

        public static void main(String[] args) throws Exception {
            sLogger.info("Starting EthereumJ!");

            // Based on Config class the BasicSample would be created by Spring
            // and its springInit() method would be called as an entry point
            EthereumFactory.createEthereum(Config.class);
        }

        public BasicSample() {
            this("sample");
        }

        /**
         * logger name can be passed if more than one EthereumJ instance is created
         * in a single JVM to distinguish logging output from different instances
         */
        public BasicSample(String loggerName) {
            this.loggerName = loggerName;
        }

        /**
         * The method is called after all EthereumJ instances are created
         */
        @PostConstruct
        private void springInit() {
            logger = LoggerFactory.getLogger(loggerName);
            // adding the main EthereumJ callback to be notified on different kind of events
            ethereum.addListener(listener);

            logger.info("Sample component created. Listening for ethereum events...");

            // starting lifecycle tracking method run()
            new Thread(this, "SampleWorkThread").start();
        }

        /**
         * The method tracks step-by-step the instance lifecycle from node discovery till sync completion.
         * At the end the method onSyncDone() is called which might be overridden by a sample subclass
         * to start making other things with the Ethereum network
         */
        public void run() {
            try {
                logger.info("Sample worker thread started.");

                if (!config.peerDiscovery()) {
                    logger.info("Peer discovery disabled. We should actively connect to another peers or wait for incoming connections");
                }

                waitForSync();

                onSyncDone();

            } catch (Exception e) {
                logger.error("Error occurred in Sample: ", e);
            }
        }


        /**
         * Waits until the whole blockchain sync is complete
         */
        private void waitForSync() throws Exception {
            logger.info("Waiting for the whole blockchain sync (will take up to several hours for the whole chain)...");
            while(true) {
                Thread.sleep(10000);

                if (synced) {
                    logger.info("[v] Sync complete! The best block: " + bestBlock.getShortDescr());
                    syncComplete = true;
                    return;
                }
            }
        }

        /**
         * Is called when the whole blockchain sync is complete
         */
        public void onSyncDone() throws Exception {
            logger.info("Monitoring new blocks in real-time...");
        }

        protected Map<Node, StatusMessage> ethNodes = new Hashtable<>();
        protected List<Node> syncPeers = new Vector<>();

        protected Block bestBlock = null;

        boolean synced = false;
        boolean syncComplete = false;

        /**
         * The main EthereumJ callback.
         */
        EthereumListener listener = new EthereumListenerAdapter() {
            @Override
            public void onSyncDone() {
                synced = true;
            }

            @Override
            public void onEthStatusUpdated(Channel channel, StatusMessage statusMessage) {
                ethNodes.put(channel.getNode(), statusMessage);
            }

            @Override
            public void onPeerAddedToSyncPool(Channel peer) {
                syncPeers.add(peer.getNode());
            }

            @Override
            public void onBlock(Block block, List<TransactionReceipt> receipts) {
                bestBlock = block;

                if (syncComplete) {
                    logger.info("New block: " + block.getShortDescr());
                }
            }
        };
    }

    /**
     * Spring configuration class for the Miner peer (A)
     */
    private static class MinerConfig {

        private final String config =
                // no need for discovery in that small network
                "peer.discovery.enabled = false \n" +
                "peer.listen.port = 30335 \n" +
                // need to have different nodeId's for the peers
                "peer.privateKey = 6ef8da380c27cea8fdf7448340ea99e8e2268fc2950d79ed47cbf6f85dc977ec \n" +
                // our private net ID
                "peer.networkId = 555 \n" +
                // we have no peers to sync with
                "sync.enabled = false \n" +
                // genesis with a lower initial difficulty and some predefined known funded accounts
                "genesis = sample-genesis.json \n" +
                // two peers need to have separate database dirs
                "database.dir = sampleDB-1 \n" +
                // when more than 1 miner exist on the network extraData helps to identify the block creator
                "mine.extraDataHex = cccccccccccccccccccc \n" +
                "mine.cpuMineThreads = 2 \n" +
                "cache.flush.blocks = 1";

        @Bean
        public MinerNode node() {
            return new MinerNode();
        }

        /**
         * Instead of supplying properties via config file for the peer
         * we are substituting the corresponding bean which returns required
         * config for this instance.
         */
        @Bean
        public SystemProperties systemProperties() {
            SystemProperties props = new SystemProperties();
            props.overrideParams(ConfigFactory.parseString(config.replaceAll("'", "\"")));
            return props;
        }
    }

    /**
     * Miner bean, which just start a miner upon creation and prints miner events
     */
    static class MinerNode extends BasicSample implements MinerListener{
        public MinerNode() {
            // peers need different loggers
            super("sampleMiner");
        }

        // overriding run() method since we don't need to wait for any discovery,
        // networking or sync events
        @Override
        public void run() {
            if (config.isMineFullDataset()) {
                logger.info("Generating Full Dataset (may take up to 10 min if not cached)...");
                // calling this just for indication of the dataset generation
                // basically this is not required
                Ethash ethash = Ethash.getForBlock(config, ethereum.getBlockchain().getBestBlock().getNumber());
                ethash.getFullDataset();
                logger.info("Full dataset generated (loaded).");
            }
            ethereum.getBlockMiner().addListener(this);
            ethereum.getBlockMiner().startMining();
        }

        @Override
        public void miningStarted() {
            logger.info("Miner started");
        }

        @Override
        public void miningStopped() {
            logger.info("Miner stopped");
        }

        @Override
        public void blockMiningStarted(Block block) {
            logger.info("Start mining block: " + block.getShortDescr());
        }

        @Override
        public void blockMined(Block block) {
            logger.info("Block mined! : \n" + block);
        }

        @Override
        public void blockMiningCanceled(Block block) {
            logger.info("Cancel mining block: " + block.getShortDescr());
        }
    }

    /**
     * Spring configuration class for the Regular peer (B)
     * It will see nodes A and C, which is not connected directly and proves that tx's from (C) reaches miner (A)
     * and new blocks both A and C
     */
    private static class RegularConfig {
        private final String config =
                // no discovery: we are connecting directly to the generator and miner peers
                "peer.discovery.enabled = false \n" +
                "peer.listen.port = 30339 \n" +
                "peer.privateKey = 1f0bbd4ffd61128a7d150c07d3f5b7dcd078359cd708ada8b60e4b9ffd90b3f5 \n" +
                "peer.networkId = 555 \n" +
                // actively connecting to the miner and tx generator
                "peer.active = [" +
                // miner
                "    { url = 'enode://26ba1aadaf59d7607ad7f437146927d79e80312f026cfa635c6b2ccf2c5d3521f5812ca2beb3b295b14f97110e6448c1c7ff68f14c5328d43a3c62b44143e9b1@localhost:30335' }, \n" +
                // tx generator
                "    { url = 'enode://3973cb86d7bef9c96e5d589601d788370f9e24670dcba0480c0b3b1b0647d13d0f0fffed115dd2d4b5ca1929287839dcd4e77bdc724302b44ae48622a8766ee6@localhost:30336' } \n" +
                "] \n" +
                "sync.enabled = true \n" +
                // all peers in the same network need to use the same genesis block
                "genesis = sample-genesis.json \n" +
                // two peers need to have separate database dirs
                "database.dir = sampleDB-2 \n";

        @Bean
        public RegularNode node() {
            return new RegularNode();
        }

        /**
         * Instead of supplying properties via config file for the peer
         * we are substituting the corresponding bean which returns required
         * config for this instance.
         */
        @Bean
        public SystemProperties systemProperties() {
            SystemProperties props = new SystemProperties();
            props.overrideParams(ConfigFactory.parseString(config.replaceAll("'", "\"")));
            return props;
        }
    }

    /**
     * This node doing nothing special, but by default as any other node will resend txs and new blocks
     */
    static class RegularNode extends BasicSample {
        public RegularNode() {
            // peers need different loggers
            super("sampleNode");
        }
    }

    /**
     * Spring configuration class for the TX-sender peer (C)
     */
    private static class GeneratorConfig {
        private final String config =
                // no discovery: forwarder will connect to us
                "peer.discovery.enabled = false \n" +
                "peer.listen.port = 30336 \n" +
                "peer.privateKey = 3ec771c31cac8c0dba77a69e503765701d3c2bb62435888d4ffa38fed60c445c \n" +
                "peer.networkId = 555 \n" +
                "sync.enabled = true \n" +
                // all peers in the same network need to use the same genesis block
                "genesis = sample-genesis.json \n" +
                // two peers need to have separate database dirs
                "database.dir = sampleDB-3 \n";

        @Bean
        public GeneratorNode node() {
            return new GeneratorNode();
        }

        /**
         * Instead of supplying properties via config file for the peer
         * we are substituting the corresponding bean which returns required
         * config for this instance.
         */
        @Bean
        public SystemProperties systemProperties() {
            SystemProperties props = new SystemProperties();
            props.overrideParams(ConfigFactory.parseString(config.replaceAll("'", "\"")));
            return props;
        }
    }

    /**
     * The tx generator node in the network which connects to the regular
     * waits for the sync and starts submitting transactions.
     * Those transactions should be included into mined blocks and the peer
     * should receive those blocks back
     */
    static class GeneratorNode extends BasicSample {
        public GeneratorNode() {
            // peers need different loggers
            super("txSenderNode");
        }

        @Override
        public void onSyncDone() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        generateTransactions();
                    } catch (Exception e) {
                        logger.error("Error generating tx: ", e);
                    }
                }
            }).start();
        }



        /**
         * Generate one simple value transfer transaction each 7 seconds.
         * Thus blocks will include one, several and none transactions
         */
        private void generateTransactions() throws Exception{
            logger.info("Start generating transactions...");

            // the sender which some coins from the genesis
            ECKey senderKey = ECKey.fromPrivate(Hex.decode("6ef8da380c27cea8fdf7448340ea99e8e2268fc2950d79ed47cbf6f85dc977ec"));
            byte[] receiverAddr = Hex.decode("5db10750e8caff27f906b41c71b3471057dd2004");

            for (int i = ethereum.getRepository().getNonce(senderKey.getAddress()).intValue(), j = 0; j < 20000; i++, j++) {
                {
                    Transaction tx = new Transaction(ByteUtil.intToBytesNoLeadZeroes(i),
                            ByteUtil.longToBytesNoLeadZeroes(50_000_000_000L), ByteUtil.longToBytesNoLeadZeroes(0xfffff),
                            receiverAddr, new byte[]{77}, new byte[0]);
                    tx.sign(senderKey);
                    logger.info("<== Submitting tx: " + tx);
                    ethereum.submitTransaction(tx);
                }
                Thread.sleep(7000);
            }
        }
    }

    private final static AtomicInteger minedBlocksBalance = new AtomicInteger(0);
    private final static AtomicInteger txsBalance = new AtomicInteger(0);
    private final static AtomicInteger fatalErrors = new AtomicInteger(0);

    private static ScheduledExecutorService statTimer =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                public Thread newThread(Runnable r) {
                    return new Thread(r, "StatTimer");
                }
            });
    /**
     *  Creating 3 EthereumJ instances with different config classes
     */
    @Test
    public void testTest() throws Exception {

        statTimer.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    testLogger.info("-------====-------");
                    testLogger.info("minedBlocksBalance: {}", minedBlocksBalance);
                    testLogger.info("txsBalance: {}", txsBalance);
                    testLogger.info("fatalErrors: {}", fatalErrors);
                    testLogger.info("-------====-------");
                    if (fatalErrors.get() > 0) {
                        statTimer.shutdownNow();
                    }
                } catch (Throwable t) {
                    testLogger.error("Unhandled exception", t);
                }
            }
        }, 0, 15, TimeUnit.SECONDS);

        testLogger.info("Starting EthereumJ miner instance!");
        Ethereum miner = EthereumFactory.createEthereum(MinerConfig.class);

        miner.addListener(new EthereumListenerAdapter() {
            @Override
            public void onBlock(BlockSummary blockSummary) {
                if (blockSummary.getBlock().getNumber() != 0L) {
                    minedBlocksBalance.incrementAndGet();
                }
            }

            @Override
            public void onRecvMessage(Channel channel, Message message) {
                super.onRecvMessage(channel, message);
                if (!(message instanceof EthMessage)) return;
                if (((EthMessage) message).getCommand().equals(EthMessageCodes.TRANSACTIONS)) {
                    txsBalance.addAndGet(-((TransactionsMessage) message).getTransactions().size());
                }
            }
        });

        testLogger.info("Starting EthereumJ regular instance!");
        EthereumFactory.createEthereum(RegularConfig.class);

        testLogger.info("Starting EthereumJ txSender instance!");
        Ethereum txGenerator = EthereumFactory.createEthereum(GeneratorConfig.class);
        txGenerator.addListener(new EthereumListenerAdapter() {
            @Override
            public void onRecvMessage(Channel channel, Message message) {
                super.onRecvMessage(channel, message);
                if (!(message instanceof EthMessage)) return;
                switch (((EthMessage) message).getCommand()) {
                    case NEW_BLOCK_HASHES:
                        testLogger.info(String.format("Received new block hash message: %s", message.toString()));
                        minedBlocksBalance.addAndGet(-((NewBlockHashesMessage) message).getBlockIdentifiers().size());
                        break;
                    case NEW_BLOCK:
                        testLogger.info(String.format("Received new block message: %s", message.toString()));
                        minedBlocksBalance.decrementAndGet();
                        break;
                    case BLOCK_BODIES:
                        testLogger.info(String.format("Received block bodies message: %s", message.toString()));
                        break;
                    case TRANSACTIONS:
                        testLogger.info(String.format("ERROR: Received new transaction message: %s", message.toString()));
                        fatalErrors.incrementAndGet();
                        throw new RuntimeException("ERROR! There shouldn't be transactions there");
                    default:
                        break;
                }
            }

            @Override
            public void onSendMessage(Channel channel, Message message) {
                super.onSendMessage(channel, message);
                if (!(message instanceof EthMessage)) return;
                if (((EthMessage) message).getCommand().equals(EthMessageCodes.TRANSACTIONS)) {
                    txsBalance.addAndGet(((TransactionsMessage) message).getTransactions().size());
                }
            }
        });

        if(statTimer.awaitTermination(2L, TimeUnit.HOURS)) {
            assert false;
        }
    }
}