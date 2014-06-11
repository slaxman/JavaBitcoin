/**
 * Copyright 2013-2014 Ronald W Hoffman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ScripterRon.JavaBitcoin;
import static org.ScripterRon.JavaBitcoin.Main.log;

import org.ScripterRon.BitcoinCore.Block;
import org.ScripterRon.BitcoinCore.InventoryItem;
import org.ScripterRon.BitcoinCore.InventoryMessage;
import org.ScripterRon.BitcoinCore.Message;
import org.ScripterRon.BitcoinCore.Transaction;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The database handler processes blocks placed on the database queue.  When a
 * block is received, the database handler validates the block and adds it
 * to the database.  This can result in the block chain being reorganized because
 * a better chain is now available.
 *
 * The database handler terminates when its shutdown() method is called.
 */
public class DatabaseHandler implements Runnable {

    /**
     * Creates the database listener
     */
    public DatabaseHandler() {
    }

    /**
     * Starts the database listener running
     */
    @Override
    public void run() {
        log.info("Database handler started");
        //
        // Process blocks until the shutdown() method is called
        //
        try {
            while (true) {
                Block block = Parameters.databaseQueue.take();
                if (block instanceof ShutdownDatabase)
                    break;
                processBlock(block);
                System.gc();
            }
        } catch (InterruptedException exc) {
            log.warn("Database handler interrupted", exc);
        } catch (Throwable exc) {
            log.error("Runtime exception while processing blocks", exc);
        }
        //
        // Stopping
        //
        log.info("Database handler stopped");
    }

    /**
     * Process a block
     *
     * @param       block           Block to process
     */
    private void processBlock(Block block) {
        try {
            //
            // Process the new block
            //
            if (Parameters.blockStore.isNewBlock(block.getHash())) {
                //
                // Store the block in our database
                //
                List<StoredBlock> chainList = Parameters.blockChain.storeBlock(block);
                //
                // Notify our peers that we have added new blocks to the chain and then
                // see if we have a child block which can now be processed.  To avoid
                // flooding peers with blocks they have already seen, we won't send an
                // 'inv' message if we are more than 3 blocks behind the best network chain.
                //
                if (chainList != null) {
                    chainList.stream().forEach((storedBlock) -> {
                        Block chainBlock = storedBlock.getBlock();
                        if (chainBlock != null) {
                            updateTxPool(chainBlock);
                            int chainHeight = storedBlock.getHeight();
                            Parameters.networkChainHeight = Math.max(chainHeight, Parameters.networkChainHeight);
                            if (chainHeight >= Parameters.networkChainHeight-3)
                                notifyPeers(storedBlock);
                        }
                    });
                    StoredBlock parentBlock = chainList.get(chainList.size()-1);
                    while (parentBlock != null)
                        parentBlock = processChildBlock(parentBlock);
                }
            }
            //
            // Remove the request from the processedRequests list
            //
            synchronized(Parameters.lock) {
                Iterator<PeerRequest> it = Parameters.processedRequests.iterator();
                while (it.hasNext()) {
                    PeerRequest request = it.next();
                    if (request.getType()==InventoryItem.INV_BLOCK && request.getHash().equals(block.getHash())) {
                        it.remove();
                        break;
                    }
                }
            }
        } catch (BlockStoreException exc) {
            log.error(String.format("Unable to store block in database\n  Block %s",
                                    block.getHashAsString()), exc);
        }
    }

    /**
     * Process a child block and see if it can now be added to the chain
     *
     * @param       storedBlock         The updated block
     * @return                          Next parent block or null
     * @throws      BlockStoreException
     */
    private StoredBlock processChildBlock(StoredBlock storedBlock) throws BlockStoreException {
        StoredBlock parentBlock = null;
        StoredBlock childStoredBlock = Parameters.blockStore.getChildStoredBlock(storedBlock.getHash());
        if (childStoredBlock != null && !childStoredBlock.isOnChain()) {
            //
            // Update the chain with the child block
            //
            Parameters.blockChain.updateBlockChain(childStoredBlock);
            if (childStoredBlock.isOnChain()) {
                updateTxPool(childStoredBlock.getBlock());
                //
                // Notify our peers about this block.  To avoid
                // flooding peers with blocks they have already seen, we won't send an
                // 'inv' message if we are more than 3 blocks behind the best network chain.
                //
                int chainHeight = childStoredBlock.getHeight();
                Parameters.networkChainHeight = Math.max(chainHeight, Parameters.networkChainHeight);
                if (chainHeight >= Parameters.networkChainHeight-3)
                    notifyPeers(childStoredBlock);
                //
                // Continue working our way up the chain
                //
                parentBlock = storedBlock;
            }
        }
        return parentBlock;
    }

    /**
     * Remove the transactions in the current block from the memory pool
     *
     * @param       block           The current block
     */
    private void updateTxPool(Block block) {
        List<Transaction> txList = block.getTransactions();
        synchronized(Parameters.lock) {
            txList.stream().map((tx) -> tx.getHash()).forEach((txHash) -> {
                StoredTransaction storedTx = Parameters.txMap.get(txHash);
                if (storedTx != null) {
                    Parameters.txPool.remove(storedTx);
                    Parameters.txMap.remove(txHash);
                }
            });
        }
    }

    /**
     * Notify peers when a block has been added to the chain
     *
     * @param       storedBlock     The stored block added to the chain
     */
    private void notifyPeers(StoredBlock storedBlock) {
        List<InventoryItem> invList = new ArrayList<>(1);
        invList.add(new InventoryItem(InventoryItem.INV_BLOCK, storedBlock.getHash()));
        Message invMsg = InventoryMessage.buildInventoryMessage(null, invList);
        invMsg.setInventoryType(InventoryItem.INV_BLOCK);
        Parameters.networkHandler.broadcastMessage(invMsg);
    }
}
