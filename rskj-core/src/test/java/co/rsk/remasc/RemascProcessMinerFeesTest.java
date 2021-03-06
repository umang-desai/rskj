/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package co.rsk.remasc;

import co.rsk.blockchain.utils.BlockchainBuilder;
import co.rsk.core.bc.BlockExecutor;
import co.rsk.crypto.Sha3Hash;
import co.rsk.peg.PegTestUtils;
import com.google.common.collect.Lists;
import co.rsk.blockchain.utils.BlockGenerator;
import co.rsk.config.RemascConfig;
import co.rsk.config.RemascConfigFactory;
import org.ethereum.config.BlockchainNetConfig;
import org.ethereum.config.SystemProperties;
import org.ethereum.config.blockchain.RegTestConfig;
import org.ethereum.core.*;
import org.ethereum.crypto.ECKey;
import org.ethereum.crypto.SHA3Helper;
import org.ethereum.vm.PrecompiledContracts;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.spongycastle.util.encoders.Hex;

import java.io.IOException;
import java.math.BigInteger;
import java.util.*;

import static org.junit.Assert.*;

public class RemascProcessMinerFeesTest {

    private static BlockchainNetConfig blockchainNetConfigOriginal;
    private static RemascConfig remascConfig;

    private BigInteger cowInitialBalance = new BigInteger("1000000000000000000");
    private long initialGasLimit = 10000000L;
    private long minerFee = 21000;
    private long txValue = 10000;
    private ECKey cowKey = ECKey.fromPrivate(SHA3Helper.sha3("cow".getBytes()));
    private byte[] cowAddress = cowKey.getAddress();
    private static Sha3Hash coinbaseA = PegTestUtils.createHash3();
    private static Sha3Hash coinbaseB = PegTestUtils.createHash3();
    private static Sha3Hash coinbaseC = PegTestUtils.createHash3();
    private static Sha3Hash coinbaseD = PegTestUtils.createHash3();
    private static Sha3Hash coinbaseE = PegTestUtils.createHash3();
    private static List<byte[]> accountsAddressesUpToD;

    private Map<byte[], BigInteger> preMineMap = new HashMap<byte[], BigInteger>() {{
        put(cowAddress, cowInitialBalance);
    }};

    private Genesis genesisBlock = (Genesis) BlockGenerator.getNewGenesisBlock(initialGasLimit, preMineMap);

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        blockchainNetConfigOriginal = SystemProperties.CONFIG.getBlockchainConfig();
        SystemProperties.CONFIG.setBlockchainConfig(new RegTestConfig());
        remascConfig = new RemascConfigFactory(RemascContract.REMASC_CONFIG).createRemascConfig("regtest");

        accountsAddressesUpToD = new LinkedList<>();
        accountsAddressesUpToD.add(coinbaseA.getBytes());
        accountsAddressesUpToD.add(coinbaseB.getBytes());
        accountsAddressesUpToD.add(coinbaseC.getBytes());
        accountsAddressesUpToD.add(coinbaseD.getBytes());
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
        SystemProperties.CONFIG.setBlockchainConfig(blockchainNetConfigOriginal);
    }

    @Test
    public void processMinersFeesWithoutRequiredMaturity() throws IOException {
        List<Block> blocks = createSimpleBlocks(genesisBlock, 1);
        Block blockWithOneTx = RemascTestRunner.createBlock(this.genesisBlock, blocks.get(blocks.size()-1), PegTestUtils.createHash3(), coinbaseA, null, minerFee, 0, txValue, cowKey);
        blocks.add(blockWithOneTx);
        blocks.addAll(createSimpleBlocks(blockWithOneTx, 1));

        BlockchainBuilder builder = new BlockchainBuilder();
        Blockchain blockchain = builder.setGenesis(genesisBlock).setTesting(true).setRsk(true).setBlocks(blocks).build();

        assertNull(blockchain.getRepository().getAccountState(coinbaseA.getBytes()));
    }

    @Test
    public void processMinersFeesWithoutMinimumSyntheticSpan() throws IOException {
        BlockchainBuilder builder = new BlockchainBuilder();
        Blockchain blockchain = builder.setTesting(true).setRsk(true).setGenesis(genesisBlock).build();

        List<Block> blocks = createSimpleBlocks(genesisBlock, 2);
        Block blockWithOneTx = RemascTestRunner.createBlock(this.genesisBlock, blocks.get(blocks.size()-1), PegTestUtils.createHash3(), coinbaseA, null, minerFee, 0, txValue, cowKey);
        blocks.add(blockWithOneTx);
        blocks.addAll(createSimpleBlocks(blockWithOneTx, 9));

        BlockExecutor blockExecutor = new BlockExecutor(blockchain.getRepository(), blockchain, blockchain.getBlockStore(), null);

        for (Block b : blocks) {
            blockExecutor.executeAndFillAll(b, blockchain.getBestBlock());
            blockchain.tryToConnect(b);
        }

        assertEquals(cowInitialBalance.subtract(BigInteger.valueOf(minerFee+txValue)), blockchain.getRepository().getAccountState(cowAddress).getBalance());

        Repository repository = blockchain.getRepository();

        assertEquals(BigInteger.valueOf(minerFee), repository.getAccountState(Hex.decode(PrecompiledContracts.REMASC_ADDR)).getBalance());
        assertNull(repository.getAccountState(coinbaseA.getBytes()));
        assertNull(repository.getAccountState(remascConfig.getRskLabsAddress()));

        Block newblock = RemascTestRunner.createBlock(this.genesisBlock, blocks.get(blocks.size()-1), PegTestUtils.createHash3(), PegTestUtils.createHash3(), null, null);

        blockExecutor.executeAndFillAll(newblock, blockchain.getBestBlock());
        blockchain.tryToConnect(newblock);

        repository = blockchain.getRepository();

        assertEquals(cowInitialBalance.subtract(BigInteger.valueOf(minerFee+txValue)), repository.getAccountState(cowAddress).getBalance());
        assertEquals(BigInteger.valueOf(minerFee), repository.getAccountState(Hex.decode(PrecompiledContracts.REMASC_ADDR)).getBalance());
        assertNull(repository.getAccountState(coinbaseA.getBytes()));
        assertNull(repository.getAccountState(remascConfig.getRskLabsAddress()));

        RemascStorageProvider remascStorageProvider = getRemascStorageProvider(blockchain);
        assertEquals(BigInteger.valueOf(minerFee), remascStorageProvider.getRewardBalance());
        assertEquals(BigInteger.ZERO, remascStorageProvider.getBurnedBalance());
        assertEquals(0, remascStorageProvider.getSiblings().size());
    }

    @Test
    public void processMinersFeesWithNoSiblings() throws IOException {
        BlockchainBuilder builder = new BlockchainBuilder();
        Blockchain blockchain = builder.setTesting(true).setRsk(true).setGenesis(genesisBlock).build();

        List<Block> blocks = createSimpleBlocks(genesisBlock, 4);
        Block blockWithOneTx = RemascTestRunner.createBlock(this.genesisBlock, blocks.get(blocks.size()-1), PegTestUtils.createHash3(), coinbaseA, null, minerFee, 0, txValue, cowKey);
        blocks.add(blockWithOneTx);
        blocks.addAll(createSimpleBlocks(blockWithOneTx, 9));

        BlockExecutor blockExecutor = new BlockExecutor(blockchain.getRepository(), blockchain, blockchain.getBlockStore(), null);

        for (Block b : blocks) {
            blockExecutor.executeAndFillAll(b, blockchain.getBestBlock());
            blockchain.tryToConnect(b);
        }

        Repository repository = blockchain.getRepository();

        assertEquals(cowInitialBalance.subtract(BigInteger.valueOf(minerFee+txValue)), repository.getAccountState(cowAddress).getBalance());
        assertEquals(BigInteger.valueOf(minerFee), repository.getAccountState(Hex.decode(PrecompiledContracts.REMASC_ADDR)).getBalance());
        assertNull(repository.getAccountState(coinbaseA.getBytes()));
        assertNull(repository.getAccountState(remascConfig.getRskLabsAddress()));

        RemascStorageProvider remascStorageProvider = getRemascStorageProvider(blockchain);

        assertEquals(BigInteger.ZERO, remascStorageProvider.getRewardBalance());
        assertEquals(BigInteger.ZERO, remascStorageProvider.getBurnedBalance());
        assertEquals(0, remascStorageProvider.getSiblings().size());

        Block newblock = RemascTestRunner.createBlock(this.genesisBlock, blocks.get(blocks.size()-1), PegTestUtils.createHash3(), PegTestUtils.createHash3(), null, null);

        blockExecutor.executeAndFillAll(newblock, blockchain.getBestBlock());
        blockchain.tryToConnect(newblock);

        repository = blockchain.getRepository();

        assertEquals(cowInitialBalance.subtract(BigInteger.valueOf(minerFee+txValue)), repository.getAccountState(cowAddress).getBalance());
        long blockReward = minerFee/remascConfig.getSyntheticSpan();
        assertEquals(BigInteger.valueOf(minerFee - blockReward), repository.getAccountState(Hex.decode(PrecompiledContracts.REMASC_ADDR)).getBalance());
        assertEquals(BigInteger.valueOf(blockReward/remascConfig.getRskLabsDivisor()), repository.getAccountState(remascConfig.getRskLabsAddress()).getBalance());
        assertEquals(BigInteger.valueOf(blockReward - blockReward/remascConfig.getRskLabsDivisor()), repository.getAccountState(coinbaseA.getBytes()).getBalance());

        remascStorageProvider = getRemascStorageProvider(blockchain);

        assertEquals(BigInteger.valueOf(minerFee - blockReward), remascStorageProvider.getRewardBalance());
        assertEquals(BigInteger.ZERO, remascStorageProvider.getBurnedBalance());
        assertEquals(0, remascStorageProvider.getSiblings().size());
    }

    @Test
    public void processMinersFeesWithOneSibling() throws IOException {
        BlockchainBuilder builder = new BlockchainBuilder();
        Blockchain blockchain = builder.setTesting(true).setRsk(true).setGenesis(genesisBlock).build();

        List<Block> blocks = createSimpleBlocks(genesisBlock, 4);
        Block blockWithOneTxA = RemascTestRunner.createBlock(this.genesisBlock, blocks.get(blocks.size()-1), PegTestUtils.createHash3(), coinbaseA, null, minerFee, 0, txValue, cowKey);
        Block blockWithOneTxB = RemascTestRunner.createBlock(this.genesisBlock, blocks.get(blocks.size()-1), PegTestUtils.createHash3(), coinbaseB, null, (long) (minerFee*1.5), 0, txValue, cowKey);
        blocks.add(blockWithOneTxA);
        Block blockThatIncludesUncle = RemascTestRunner.createBlock(this.genesisBlock, blockWithOneTxA, PegTestUtils.createHash3(), coinbaseC, Lists.newArrayList(blockWithOneTxB.getHeader()), null);
        blocks.add(blockThatIncludesUncle);
        blocks.addAll(createSimpleBlocks(blockThatIncludesUncle, 8));

        BlockExecutor blockExecutor = new BlockExecutor(blockchain.getRepository(), blockchain, blockchain.getBlockStore(), null);

        for (Block b : blocks) {
            blockExecutor.executeAndFillAll(b, blockchain.getBestBlock());
            blockchain.tryToConnect(b);
        }

        Repository repository = blockchain.getRepository();

        assertEquals(cowInitialBalance.subtract(BigInteger.valueOf(minerFee+txValue)), repository.getAccountState(cowAddress).getBalance());
        assertEquals(BigInteger.valueOf(minerFee), repository.getAccountState(Hex.decode(PrecompiledContracts.REMASC_ADDR)).getBalance());
        assertNull(repository.getAccountState(coinbaseA.getBytes()));
        assertNull(repository.getAccountState(coinbaseB.getBytes()));
        assertNull(repository.getAccountState(coinbaseC.getBytes()));
        assertNull(repository.getAccountState(remascConfig.getRskLabsAddress()));

        RemascStorageProvider remascStorageProvider = getRemascStorageProvider(blockchain);

        assertEquals(BigInteger.ZERO, remascStorageProvider.getRewardBalance());
        assertEquals(BigInteger.ZERO, remascStorageProvider.getBurnedBalance());
        assertEquals(1, remascStorageProvider.getSiblings().size());

        Block newblock = RemascTestRunner.createBlock(this.genesisBlock, blocks.get(blocks.size()-1), PegTestUtils.createHash3(), PegTestUtils.createHash3(), null);

        blockExecutor.executeAndFillAll(newblock, blockchain.getBestBlock());

        blockchain.tryToConnect(newblock);

        repository = blockchain.getRepository();

        assertEquals(cowInitialBalance.subtract(BigInteger.valueOf(minerFee+txValue)), repository.getAccountState(cowAddress).getBalance());
        long blockReward = minerFee/remascConfig.getSyntheticSpan();
        assertEquals(BigInteger.valueOf(minerFee-blockReward), repository.getAccountState(Hex.decode(PrecompiledContracts.REMASC_ADDR)).getBalance());
        assertEquals(BigInteger.valueOf(blockReward/remascConfig.getRskLabsDivisor()), repository.getAccountState(remascConfig.getRskLabsAddress()).getBalance());
        blockReward = blockReward - blockReward/remascConfig.getRskLabsDivisor();
        assertEquals(BigInteger.valueOf(blockReward/remascConfig.getPublishersDivisor()), repository.getAccountState(coinbaseC.getBytes()).getBalance());
        blockReward = blockReward - blockReward/remascConfig.getPublishersDivisor();
        assertEquals(BigInteger.valueOf(blockReward/2), repository.getAccountState(coinbaseA.getBytes()).getBalance());
        assertEquals(BigInteger.valueOf(blockReward/2), repository.getAccountState(coinbaseB.getBytes()).getBalance());

        blockReward = minerFee/remascConfig.getSyntheticSpan();

        remascStorageProvider = getRemascStorageProvider(blockchain);

        assertEquals(BigInteger.valueOf(minerFee - blockReward), remascStorageProvider.getRewardBalance());
        assertEquals(BigInteger.ZERO, remascStorageProvider.getBurnedBalance());
        assertEquals(0, remascStorageProvider.getSiblings().size());
    }

    @Test
    public void processMinersFeesWithOneSiblingBrokenSelectionRuleBlockWithHigherFees() throws IOException {
        processMinersFeesWithOneSiblingBrokenSelectionRule("higherFees");
    }

    @Test
    public void processMinersFeesWithOneSiblingBrokenSelectionRuleBlockWithLowerHash() throws IOException, ClassNotFoundException {
        processMinersFeesWithOneSiblingBrokenSelectionRule("lowerHash");
    }

    @Test
    public void siblingThatBreaksSelectionRuleGetsPunished() throws IOException {
        BlockchainBuilder builder = new BlockchainBuilder();
        Blockchain blockchain = builder.setTesting(true).setRsk(true).setGenesis(genesisBlock).build();

        final long NUMBER_OF_TXS_WITH_FEES = 3;
        List<Block> blocks = createSimpleBlocks(genesisBlock, 4);

        Block blockAtHeightThree = blocks.get(blocks.size() - 1);
        Block blockWithOneTxA = RemascTestRunner.createBlock(this.genesisBlock, blockAtHeightThree, PegTestUtils.createHash3(), coinbaseA, null, minerFee, 0, txValue, cowKey);
        blocks.add(blockWithOneTxA);

        Block blockWithOneTxC = RemascTestRunner.createBlock(this.genesisBlock, blockWithOneTxA, PegTestUtils.createHash3(), coinbaseC, null, minerFee, 1, txValue, cowKey);
        blocks.add(blockWithOneTxC);

        Block blockWithOneTxD = RemascTestRunner.createBlock(this.genesisBlock, blockWithOneTxA, PegTestUtils.createHash3(), coinbaseD, null, minerFee, 1, txValue, cowKey);
        Block blockWithOneTxB = RemascTestRunner.createBlock(this.genesisBlock, blockAtHeightThree, PegTestUtils.createHash3(), coinbaseB, null, 3 * minerFee, 0, txValue, cowKey);

        Block blockThatIncludesUnclesE = RemascTestRunner.createBlock(this.genesisBlock, blockWithOneTxC, PegTestUtils.createHash3(), coinbaseE, Lists.newArrayList(blockWithOneTxB.getHeader(), blockWithOneTxD.getHeader()), minerFee, 2, txValue, cowKey);
        blocks.add(blockThatIncludesUnclesE);
        blocks.addAll(createSimpleBlocks(blockThatIncludesUnclesE, 7));

        BlockExecutor blockExecutor = new BlockExecutor(blockchain.getRepository(), blockchain, blockchain.getBlockStore(), null);

        for (Block b : blocks) {
            blockExecutor.executeAndFillAll(b, blockchain.getBestBlock());
            blockchain.tryToConnect(b);
        }

        // validate that the blockchain's and REMASC's initial states are correct
        BigInteger cowRemainingBalance = cowInitialBalance.subtract(BigInteger.valueOf(minerFee * NUMBER_OF_TXS_WITH_FEES + txValue * NUMBER_OF_TXS_WITH_FEES));
        List<Long> otherAccountsBalance = new ArrayList<>(Arrays.asList(null, null, null, null));
        this.validateAccountsCurrentBalanceIsCorrect(blockchain.getRepository(), cowRemainingBalance, minerFee * NUMBER_OF_TXS_WITH_FEES, null, this.getAccountsWithExpectedBalance(otherAccountsBalance));
        this.validateRemascsStorageIsCorrect(this.getRemascStorageProvider(blockchain), BigInteger.ZERO, BigInteger.ZERO, 2L);

        // add block to pay fees of blocks on blockchain's height 4
        Block blockToPayFeesOnHeightFour = RemascTestRunner.createBlock(this.genesisBlock, blocks.get(blocks.size() - 1), PegTestUtils.createHash3(), PegTestUtils.createHash3(), null, minerFee, 3, txValue, cowKey);

        blockExecutor.executeAndFillAll(blockToPayFeesOnHeightFour, blockchain.getBestBlock());

        blockchain.tryToConnect(blockToPayFeesOnHeightFour);

        Repository repository = blockchain.getRepository();

        // -- After executing REMASC's contract for paying height 4 block
        // validate that account's balances are correct
        cowRemainingBalance = cowRemainingBalance.subtract(BigInteger.valueOf(minerFee + txValue));
        long minerRewardOnHeightFour = minerFee / remascConfig.getSyntheticSpan();
        long burnBalanceLevelFour = minerRewardOnHeightFour;
        long remascCurrentBalance = minerFee * 4 - burnBalanceLevelFour;
        long rskCurrentBalance = minerRewardOnHeightFour / remascConfig.getRskLabsDivisor();
        minerRewardOnHeightFour -= minerRewardOnHeightFour / remascConfig.getRskLabsDivisor();
        long publishersFee = minerRewardOnHeightFour / remascConfig.getPublishersDivisor();
        minerRewardOnHeightFour -= minerRewardOnHeightFour / remascConfig.getPublishersDivisor();
        minerRewardOnHeightFour /= 2;
        long siblingPunishmentLvlFour = (long)(minerRewardOnHeightFour *  0.05);
        long siblingReward = minerRewardOnHeightFour - siblingPunishmentLvlFour;

        HashMap<byte[], BigInteger> otherAccountsBalanceOnHeightFour = this.getAccountsWithExpectedBalance(new ArrayList<>(Arrays.asList(minerRewardOnHeightFour, siblingReward, null, null)));
        otherAccountsBalanceOnHeightFour.put(coinbaseE.getBytes(), BigInteger.valueOf(publishersFee));
        remascCurrentBalance += siblingPunishmentLvlFour;
        this.validateAccountsCurrentBalanceIsCorrect(repository, cowRemainingBalance, remascCurrentBalance, rskCurrentBalance, otherAccountsBalanceOnHeightFour);
        // validate that REMASC's state is correct
        long blockRewardOnHeightFour = minerFee / remascConfig.getSyntheticSpan();
        BigInteger expectedRewardBalance = BigInteger.valueOf(minerFee - blockRewardOnHeightFour);
        BigInteger expectedBurnedBalance = BigInteger.valueOf(siblingPunishmentLvlFour);
        this.validateRemascsStorageIsCorrect(this.getRemascStorageProvider(blockchain), expectedRewardBalance, expectedBurnedBalance, 1L);

        // add block to pay fees of blocks on blockchain's height 5
        Block blockToPayFeesOnHeightFive = RemascTestRunner.createBlock(this.genesisBlock, blockToPayFeesOnHeightFour, PegTestUtils.createHash3(), PegTestUtils.createHash3(), null, minerFee, 4, txValue, cowKey);
        blockExecutor.executeAndFillAll(blockToPayFeesOnHeightFive, blockchain.getBestBlock());
        blockchain.tryToConnect(blockToPayFeesOnHeightFive);

        repository = blockchain.getRepository();

        // -- After executing REMASC's contract for paying height 5 block
        // validate that account's balances are correct
        cowRemainingBalance = cowRemainingBalance.subtract(BigInteger.valueOf(minerFee + txValue));
        long rewardBalance = minerFee - blockRewardOnHeightFour;
        rewardBalance += minerFee;
        long blockRewardOnHeightFive = rewardBalance / remascConfig.getSyntheticSpan();
        remascCurrentBalance += minerFee - blockRewardOnHeightFive;
        rskCurrentBalance += blockRewardOnHeightFive / remascConfig.getRskLabsDivisor();
        blockRewardOnHeightFive -= blockRewardOnHeightFive / remascConfig.getRskLabsDivisor();

        long publishersFeeOnHeightFive = blockRewardOnHeightFive / remascConfig.getPublishersDivisor();
        blockRewardOnHeightFive -= publishersFeeOnHeightFive;

        long numberOfSiblingsOnHeightFive = 2;
        blockRewardOnHeightFive /= numberOfSiblingsOnHeightFive;

        long punishmentFee = blockRewardOnHeightFive / remascConfig.getPunishmentDivisor();
        blockRewardOnHeightFive -= punishmentFee;
        remascCurrentBalance += (numberOfSiblingsOnHeightFive * punishmentFee);

        HashMap<byte[], BigInteger> otherAccountsBalanceOnHeightFive = this.getAccountsWithExpectedBalance(new ArrayList<>(Arrays.asList(minerRewardOnHeightFour, siblingReward, blockRewardOnHeightFive, blockRewardOnHeightFive)));
        otherAccountsBalanceOnHeightFive.put(coinbaseE.getBytes(), BigInteger.valueOf(publishersFee + publishersFeeOnHeightFive));
        this.validateAccountsCurrentBalanceIsCorrect(repository, cowRemainingBalance, remascCurrentBalance, rskCurrentBalance, otherAccountsBalanceOnHeightFive);
        // validate that REMASC's state is correct
        blockRewardOnHeightFive = (2 * minerFee - blockRewardOnHeightFour ) / remascConfig.getSyntheticSpan();
        expectedRewardBalance = BigInteger.valueOf(minerFee * 2 - blockRewardOnHeightFour - blockRewardOnHeightFive);
        expectedBurnedBalance = BigInteger.valueOf((2 * punishmentFee) + siblingPunishmentLvlFour);
        this.validateRemascsStorageIsCorrect(this.getRemascStorageProvider(blockchain), expectedRewardBalance, expectedBurnedBalance, 0L);
    }

    @Test
    public void noPublisherFeeIsPaidWhenThePublisherHasNoSiblings() throws IOException {
        BlockchainBuilder builder = new BlockchainBuilder();
        Blockchain blockchain = builder.setBlockStore(new RemascTestBlockStore()).setTesting(true).setRsk(true).setGenesis(genesisBlock).build();

        final long NUMBER_OF_TXS_WITH_FEES = 3;
        List<Block> blocks = createSimpleBlocks(genesisBlock, 4);
        Block blockWithOneTxD = RemascTestRunner.createBlock(this.genesisBlock, blocks.get(blocks.size()-1), PegTestUtils.createHash3(), coinbaseD, null, minerFee, 0, txValue, cowKey);
        blocks.add(blockWithOneTxD);

        Block blockWithOneTxA = RemascTestRunner.createBlock(this.genesisBlock, blockWithOneTxD, PegTestUtils.createHash3(), coinbaseA, null, minerFee, 1, txValue, cowKey);
        Block blockWithOneTxB = RemascTestRunner.createBlock(this.genesisBlock, blockWithOneTxD, PegTestUtils.createHash3(), coinbaseB, null, minerFee * 3, 1, txValue, cowKey);
        blocks.add(blockWithOneTxA);

        Block blockThatIncludesUncleC = RemascTestRunner.createBlock(this.genesisBlock, blockWithOneTxA, PegTestUtils.createHash3(), coinbaseC, Lists.newArrayList(blockWithOneTxB.getHeader()), minerFee, 2, txValue, cowKey);
        blocks.add(blockThatIncludesUncleC);
        blocks.addAll(createSimpleBlocks(blockThatIncludesUncleC, 7));

        BlockExecutor blockExecutor = new BlockExecutor(blockchain.getRepository(), blockchain, blockchain.getBlockStore(), null);

        for (Block b : blocks) {
            blockExecutor.executeAndFillAll(b, blockchain.getBestBlock());
            blockchain.tryToConnect(b);
        }

        // validate that the blockchain's and REMASC's initial states are correct
        BigInteger cowRemainingBalance = cowInitialBalance.subtract(BigInteger.valueOf(minerFee * NUMBER_OF_TXS_WITH_FEES + txValue * NUMBER_OF_TXS_WITH_FEES));
        List<Long> otherAccountsBalance = new ArrayList<>(Arrays.asList(null, null, null, null));
        this.validateAccountsCurrentBalanceIsCorrect(blockchain.getRepository(), cowRemainingBalance, minerFee * NUMBER_OF_TXS_WITH_FEES, null, this.getAccountsWithExpectedBalance(otherAccountsBalance));
        this.validateRemascsStorageIsCorrect(this.getRemascStorageProvider(blockchain), BigInteger.ZERO, BigInteger.ZERO, 1L);

        // add block to pay fees of blocks on blockchain's height 4
        Block blockToPayFeesOnHeightFour = RemascTestRunner.createBlock(this.genesisBlock, blocks.get(blocks.size() - 1), PegTestUtils.createHash3(), PegTestUtils.createHash3(), null, minerFee, 0, txValue, cowKey);
        blockExecutor.executeAndFillAll(blockToPayFeesOnHeightFour, blockchain.getBestBlock());
        blockchain.tryToConnect(blockToPayFeesOnHeightFour);

        Repository repository = blockchain.getRepository();

        // -- After executing REMASC's contract for paying height 4 block
        // validate that account's balances are correct
        long blockRewardOnHeightFour = minerFee / remascConfig.getSyntheticSpan();
        long remascCurrentBalance = minerFee * 3 - blockRewardOnHeightFour;
        long rskCurrentBalance = blockRewardOnHeightFour / remascConfig.getRskLabsDivisor();
        blockRewardOnHeightFour -= blockRewardOnHeightFour / remascConfig.getRskLabsDivisor();
        List<Long> otherAccountsBalanceOnHeightFour = new ArrayList<>(Arrays.asList(null, null, null, blockRewardOnHeightFour));
        this.validateAccountsCurrentBalanceIsCorrect(repository, cowRemainingBalance, remascCurrentBalance, rskCurrentBalance, this.getAccountsWithExpectedBalance(otherAccountsBalanceOnHeightFour));
        // validate that REMASC's state is correct
        blockRewardOnHeightFour = minerFee / remascConfig.getSyntheticSpan();
        BigInteger expectedRewardBalance = BigInteger.valueOf(minerFee - blockRewardOnHeightFour);
        this.validateRemascsStorageIsCorrect(this.getRemascStorageProvider(blockchain), expectedRewardBalance, BigInteger.ZERO, 1L);
    }

    private void processMinersFeesWithOneSiblingBrokenSelectionRule(String reasonForBrokenSelectionRule) throws IOException {
        BlockchainBuilder builder = new BlockchainBuilder();
        Blockchain blockchain = builder.setBlockStore(new RemascTestBlockStore()).setTesting(true).setRsk(true).setGenesis(genesisBlock).build();

        final long NUMBER_OF_TXS_WITH_FEES = 3;
        List<Block> blocks = createSimpleBlocks(this.genesisBlock, 4);
        Block blockWithOneTxA;
        Block blockWithOneTxB;

        if ("higherFees".equals(reasonForBrokenSelectionRule)) {
            blockWithOneTxA = RemascTestRunner.createBlock(this.genesisBlock, blocks.get(blocks.size()-1), PegTestUtils.createHash3(), coinbaseA, null, minerFee, 0, txValue, cowKey);
            blockWithOneTxB = RemascTestRunner.createBlock(this.genesisBlock, blocks.get(blocks.size()-1), PegTestUtils.createHash3(), coinbaseB, null, minerFee * 3, 0, txValue, cowKey);
        } else {
            Sha3Hash blockWithOneTxBHash = PegTestUtils.createHash3();
            Sha3Hash blockWithOneTxAHash = PegTestUtils.createHash3();
            blockWithOneTxA = RemascTestRunner.createBlock(this.genesisBlock, blocks.get(blocks.size()-1), blockWithOneTxAHash, coinbaseA, null, minerFee, 0, txValue, cowKey);
            blockWithOneTxB = RemascTestRunner.createBlock(this.genesisBlock, blocks.get(blocks.size()-1), blockWithOneTxBHash, coinbaseB, null, (long) (minerFee * 1.5), 0, txValue, cowKey);
        }

        blocks.add(blockWithOneTxA);
        Block blockThatIncludesUncleC = RemascTestRunner.createBlock(this.genesisBlock, blockWithOneTxA, PegTestUtils.createHash3(), coinbaseC, Lists.newArrayList(blockWithOneTxB.getHeader()), minerFee, 1, txValue, cowKey);
        blocks.add(blockThatIncludesUncleC);
        Block blockWithOneTxD = RemascTestRunner.createBlock(this.genesisBlock, blockThatIncludesUncleC, PegTestUtils.createHash3(), coinbaseD, null, minerFee, 2, txValue, cowKey);
        blocks.add(blockWithOneTxD);
        blocks.addAll(createSimpleBlocks(blockWithOneTxD, 7));

        BlockExecutor blockExecutor = new BlockExecutor(blockchain.getRepository(), blockchain, blockchain.getBlockStore(), null);

        for (Block b : blocks) {
            blockExecutor.executeAndFillAll(b, blockchain.getBestBlock());
            blockchain.tryToConnect(b);
        }

        // validate that the blockchain's and REMASC's initial states are correct
        BigInteger cowRemainingBalance = cowInitialBalance.subtract(BigInteger.valueOf(minerFee * NUMBER_OF_TXS_WITH_FEES + txValue * NUMBER_OF_TXS_WITH_FEES));
        List<Long> otherAccountsBalance = new ArrayList<>(Arrays.asList(null, null, null, null));
        this.validateAccountsCurrentBalanceIsCorrect(blockchain.getRepository(), cowRemainingBalance, minerFee * NUMBER_OF_TXS_WITH_FEES, null, this.getAccountsWithExpectedBalance(otherAccountsBalance));
        this.validateRemascsStorageIsCorrect(this.getRemascStorageProvider(blockchain), BigInteger.ZERO, BigInteger.ZERO, 1L);

        // add block to pay fees of blocks on blockchain's height 5
        Block blockToPayFeesOnHeightFive = RemascTestRunner.createBlock(this.genesisBlock, blocks.get(blocks.size() - 1), PegTestUtils.createHash3(), PegTestUtils.createHash3(), null, null);
        blockExecutor.executeAndFillAll(blockToPayFeesOnHeightFive, blockchain.getBestBlock());
        blockchain.tryToConnect(blockToPayFeesOnHeightFive);

        // -- After executing REMASC's contract for paying height 5 blocks
        // validate that account's balances are correct
        long blockRewardOnHeightFive = minerFee / remascConfig.getSyntheticSpan();
        long remascCurrentBalance = minerFee * 3 - blockRewardOnHeightFive;
        long rskCurrentBalance = blockRewardOnHeightFive / remascConfig.getRskLabsDivisor();
        blockRewardOnHeightFive -= blockRewardOnHeightFive / remascConfig.getRskLabsDivisor();
        long publisherReward = blockRewardOnHeightFive / remascConfig.getPublishersDivisor();
        blockRewardOnHeightFive -= blockRewardOnHeightFive / remascConfig.getPublishersDivisor();
        long minerRewardOnHeightFive = blockRewardOnHeightFive / 2;
        List<Long> otherAccountsBalanceOnHeightFive = new ArrayList<>(Arrays.asList(minerRewardOnHeightFive, minerRewardOnHeightFive, publisherReward, null));
        this.validateAccountsCurrentBalanceIsCorrect(blockchain.getRepository(), cowRemainingBalance, remascCurrentBalance, rskCurrentBalance, this.getAccountsWithExpectedBalance(otherAccountsBalanceOnHeightFive));
        // validate that REMASC's state is correct
        blockRewardOnHeightFive = minerFee / remascConfig.getSyntheticSpan();
        BigInteger expectedRewardBalance = BigInteger.valueOf(minerFee - blockRewardOnHeightFive);
        this.validateRemascsStorageIsCorrect(this.getRemascStorageProvider(blockchain), expectedRewardBalance, BigInteger.ZERO, 0L);

        // add block to pay fees of blocks on blockchain's height 6
        Block blockToPayFeesOnHeightSix = RemascTestRunner.createBlock(this.genesisBlock, blockToPayFeesOnHeightFive, PegTestUtils.createHash3(), PegTestUtils.createHash3(), null, null);
        blockExecutor.executeAndFillAll(blockToPayFeesOnHeightSix, blockchain.getBestBlock());
        blockchain.tryToConnect(blockToPayFeesOnHeightSix);

        // -- After executing REMASC's contract for paying height 6 blocks
        // validate that account's balances are correct
        long rewardBalance = minerFee - blockRewardOnHeightFive + minerFee;
        long blockRewardOnHeightSix = rewardBalance / remascConfig.getSyntheticSpan();
        rewardBalance -= blockRewardOnHeightSix;

        long blockRewardWithoutRskFee = blockRewardOnHeightSix - blockRewardOnHeightSix / remascConfig.getRskLabsDivisor();
        long burnedBalance = blockRewardWithoutRskFee / remascConfig.getPunishmentDivisor();

        remascCurrentBalance = minerFee * NUMBER_OF_TXS_WITH_FEES - blockRewardOnHeightFive - blockRewardOnHeightSix + burnedBalance;
        rskCurrentBalance += blockRewardOnHeightSix / remascConfig.getRskLabsDivisor();
        blockRewardOnHeightSix -= blockRewardOnHeightSix / remascConfig.getRskLabsDivisor();
        blockRewardOnHeightSix -= blockRewardOnHeightSix / remascConfig.getPunishmentDivisor();
        List<Long> otherAccountsBalanceOnHeightSix = new ArrayList<>(Arrays.asList(minerRewardOnHeightFive, minerRewardOnHeightFive, publisherReward + blockRewardOnHeightSix, null));
        this.validateAccountsCurrentBalanceIsCorrect(blockchain.getRepository(), cowRemainingBalance, remascCurrentBalance, rskCurrentBalance, this.getAccountsWithExpectedBalance(otherAccountsBalanceOnHeightSix));
        this.validateRemascsStorageIsCorrect(this.getRemascStorageProvider(blockchain), BigInteger.valueOf(rewardBalance), BigInteger.valueOf(burnedBalance), 0L);
    }

    @Test
    public void processMinersFeesFromTxThatIsNotTheLatestTx() throws IOException {
        BlockchainBuilder builder = new BlockchainBuilder();
        Blockchain blockchain = builder.setBlockStore(new RemascTestBlockStore()).setTesting(true).setRsk(true).setGenesis(genesisBlock).build();

        List<Block> blocks = createSimpleBlocks(genesisBlock, 4);
        Block blockWithOneTx = RemascTestRunner.createBlock(this.genesisBlock, blocks.get(blocks.size()-1), PegTestUtils.createHash3(), coinbaseA, null, minerFee, 0, txValue, cowKey);
        blocks.add(blockWithOneTx);
        blocks.addAll(createSimpleBlocks(blockWithOneTx, 9));

        BlockExecutor blockExecutor = new BlockExecutor(blockchain.getRepository(), blockchain, blockchain.getBlockStore(), null);

        for (Block b : blocks) {
            blockExecutor.executeAndFillAll(b, blockchain.getBestBlock());
            blockchain.tryToConnect(b);
        }

        Repository repository = blockchain.getRepository();

        assertEquals(cowInitialBalance.subtract(BigInteger.valueOf(minerFee+txValue)), repository.getAccountState(cowAddress).getBalance());
        assertEquals(BigInteger.valueOf(minerFee), repository.getAccountState(Hex.decode(PrecompiledContracts.REMASC_ADDR)).getBalance());
        assertNull(repository.getAccountState(coinbaseA.getBytes()));
        assertNull(repository.getAccountState(remascConfig.getRskLabsAddress()));

        RemascStorageProvider remasceStorageProvider = getRemascStorageProvider(blockchain);
        assertEquals(BigInteger.ZERO, remasceStorageProvider.getRewardBalance());
        assertEquals(BigInteger.ZERO, remasceStorageProvider.getBurnedBalance());
        assertEquals(0, remasceStorageProvider.getSiblings().size());

        // A hacker trying to screw the system creates a tx to remasc and a fool/accomplice miner includes that tx in a block
        Transaction tx = new Transaction(
                BigInteger.valueOf(1).toByteArray(),
                BigInteger.ONE.toByteArray(),
                BigInteger.valueOf(minerFee).toByteArray(),
                Hex.decode(PrecompiledContracts.REMASC_ADDR) ,
                BigInteger.valueOf(txValue*2).toByteArray(),
                null,
                Transaction.getConfigChainId());
        tx.sign(cowKey.getPrivKeyBytes());
        Block newblock = RemascTestRunner.createBlock(this.genesisBlock, blocks.get(blocks.size()-1), PegTestUtils.createHash3(), PegTestUtils.createHash3(), null, tx);
        blockExecutor.executeAndFillAll(newblock, blockchain.getBestBlock());
        blockchain.tryToConnect(newblock);

        repository = blockchain.getRepository();

        // Check "hack" tx makes no changes to the remasc state, sender pays fees, and value is added to remasc account balance
        assertEquals(cowInitialBalance.subtract(BigInteger.valueOf(minerFee+txValue+minerFee)), repository.getAccountState(cowAddress).getBalance());
        long blockReward = minerFee/remascConfig.getSyntheticSpan();
        assertEquals(BigInteger.valueOf(minerFee+minerFee-blockReward), repository.getAccountState(Hex.decode(PrecompiledContracts.REMASC_ADDR)).getBalance());
        assertEquals(BigInteger.valueOf(blockReward/remascConfig.getRskLabsDivisor()),repository.getAccountState(remascConfig.getRskLabsAddress()).getBalance());
        assertEquals(BigInteger.valueOf(blockReward - blockReward/remascConfig.getRskLabsDivisor()), repository.getAccountState(coinbaseA.getBytes()).getBalance());

        BigInteger expectedRewardBalance = BigInteger.valueOf(minerFee - blockReward);
        this.validateRemascsStorageIsCorrect(this.getRemascStorageProvider(blockchain), expectedRewardBalance, BigInteger.ZERO, 0L);
    }

    @Test
    public void processMinersFeesFromTxInvokedByAnotherContract() throws IOException {
        BlockchainBuilder builder = new BlockchainBuilder();
        Blockchain blockchain = builder.setBlockStore(new RemascTestBlockStore()).setTesting(true).setRsk(true).setGenesis(genesisBlock).build();

        List<Block> blocks = createSimpleBlocks(genesisBlock, 4);
        Block blockWithOneTx = RemascTestRunner.createBlock(this.genesisBlock, blocks.get(blocks.size()-1), PegTestUtils.createHash3(), coinbaseA, null, minerFee, 0, txValue, cowKey);
        blocks.add(blockWithOneTx);
        blocks.addAll(createSimpleBlocks(blockWithOneTx, 9));

        BlockExecutor blockExecutor = new BlockExecutor(blockchain.getRepository(), blockchain, blockchain.getBlockStore(), null);

        for (Block b : blocks) {
            blockExecutor.executeAndFillAll(b, blockchain.getBestBlock());
            blockchain.tryToConnect(b);
        }

        Repository repository = blockchain.getRepository();

        assertEquals(cowInitialBalance.subtract(BigInteger.valueOf(minerFee+txValue)), repository.getAccountState(cowAddress).getBalance());
        assertEquals(BigInteger.valueOf(minerFee), repository.getAccountState(Hex.decode(PrecompiledContracts.REMASC_ADDR)).getBalance());
        assertNull(repository.getAccountState(coinbaseA.getBytes()));
        assertNull(repository.getAccountState(remascConfig.getRskLabsAddress()));

        RemascStorageProvider remasceStorageProvider = getRemascStorageProvider(blockchain);
        assertEquals(BigInteger.ZERO, remasceStorageProvider.getRewardBalance());
        assertEquals(BigInteger.ZERO, remasceStorageProvider.getBurnedBalance());
        assertEquals(0, remasceStorageProvider.getSiblings().size());

        // A hacker trying to screw the system creates a contracts that calls remasc and a fool/accomplice miner includes that tx in a block
//        Contract code
//        pragma solidity ^0.4.3;
//        contract RemascHacker {
//
//            function()
//            {
//                address remasc = 0x0000000000000000000000000000000001000008;
//                remasc.call();
//            }
//        }
        long txCreateContractGasLimit = 53755;
        Transaction txCreateContract = new Transaction(
                BigInteger.valueOf(1).toByteArray(),
                BigInteger.ONE.toByteArray(),
                BigInteger.valueOf(txCreateContractGasLimit).toByteArray(),
                null ,
                BigInteger.valueOf(0).toByteArray(),
                Hex.decode("6060604052346000575b6077806100176000396000f30060606040525b3460005760495b6000600890508073ffffffffffffffffffffffffffffffffffffffff166040518090506000604051808303816000866161da5a03f1915050505b50565b0000a165627a7a7230582036692fbb1395da1688af0189be5b0ac18df3d93a2402f4fc8f927b31c1baa2460029"),
                Transaction.getConfigChainId());
        txCreateContract.sign(cowKey.getPrivKeyBytes());
        long txCallRemascGasLimit = 21828;
        Transaction txCallRemasc = new Transaction(
                BigInteger.valueOf(2).toByteArray(),
                BigInteger.ONE.toByteArray(),
                BigInteger.valueOf(txCallRemascGasLimit).toByteArray(),
                Hex.decode("da7ce79725418f4f6e13bf5f520c89cec5f6a974") ,
                BigInteger.valueOf(0).toByteArray(),
                null,
                Transaction.getConfigChainId());
        txCallRemasc.sign(cowKey.getPrivKeyBytes());

        Block newblock = RemascTestRunner.createBlock(this.genesisBlock, blocks.get(blocks.size()-1), PegTestUtils.createHash3(), PegTestUtils.createHash3(), null, txCreateContract, txCallRemasc);
        blockExecutor.executeAndFillAll(newblock, blockchain.getBestBlock());
        blockchain.tryToConnect(newblock);

        repository = blockchain.getRepository();

        // Check "hack" tx makes no changes to the remasc state, sender pays fees, and value is added to remasc account balance
        assertEquals(cowInitialBalance.subtract(BigInteger.valueOf(txCreateContractGasLimit+txCallRemascGasLimit+txValue+minerFee)), repository.getAccountState(cowAddress).getBalance());
        long blockReward = minerFee/remascConfig.getSyntheticSpan();
        assertEquals(BigInteger.valueOf(txCreateContractGasLimit+txCallRemascGasLimit+minerFee-blockReward), repository.getAccountState(Hex.decode(PrecompiledContracts.REMASC_ADDR)).getBalance());
        assertEquals(BigInteger.valueOf(blockReward/remascConfig.getRskLabsDivisor()), repository.getAccountState(remascConfig.getRskLabsAddress()).getBalance());
        assertEquals(BigInteger.valueOf(blockReward - blockReward/remascConfig.getRskLabsDivisor()), repository.getAccountState(coinbaseA.getBytes()).getBalance());

        BigInteger expectedRewardBalance = BigInteger.valueOf(minerFee - blockReward);
        this.validateRemascsStorageIsCorrect(this.getRemascStorageProvider(blockchain), expectedRewardBalance, BigInteger.ZERO, 0L);
    }

    @Test
    public void siblingIncludedOneBlockLater() throws IOException {

        BlockchainBuilder builder = new BlockchainBuilder().setBlockStore(new RemascTestBlockStore()).setTesting(true).setRsk(true).setGenesis(genesisBlock);

        List<SiblingElement> siblings = Lists.newArrayList(new SiblingElement(5, 7, this.minerFee));

        RemascTestRunner testRunner = new RemascTestRunner(builder, this.genesisBlock).txValue(txValue).minerFee(this.minerFee)
                .initialHeight(15).siblingElements(siblings).txSigningKey(this.cowKey);

        testRunner.start();

        Blockchain blockchain = testRunner.getBlockChain();

        Block blockAtHeightSeven = blockchain.getBlockByNumber(8);
        assertEquals(BigInteger.valueOf(1680L), testRunner.getAccountBalance(blockAtHeightSeven.getCoinbase()));

        Block blockAtHeightFiveMainchain = blockchain.getBlockByNumber(6);
        assertEquals(BigInteger.valueOf(7560L), testRunner.getAccountBalance(blockAtHeightFiveMainchain.getCoinbase()));

        Block blockAtHeightFiveSibling = testRunner.getAddedSiblings().get(0);
        assertEquals(BigInteger.valueOf(7182L), testRunner.getAccountBalance(blockAtHeightFiveSibling.getCoinbase()));

        this.validateRemascsStorageIsCorrect(this.getRemascStorageProvider(blockchain), BigInteger.valueOf(84000L), BigInteger.valueOf(378L), 0L);
    }

    @Test
    public void oneSiblingIncludedOneBlockLaterAndAnotherIncludedRightAfter() throws IOException {

        BlockchainBuilder builder = new BlockchainBuilder().setBlockStore(new RemascTestBlockStore()).setTesting(true).setRsk(true).setGenesis(genesisBlock);

        List<SiblingElement> siblings = Lists.newArrayList(new SiblingElement(5, 6, this.minerFee), new SiblingElement(5, 7, this.minerFee));

        RemascTestRunner testRunner = new RemascTestRunner(builder, this.genesisBlock).txValue(txValue).minerFee(this.minerFee)
                .initialHeight(15).siblingElements(siblings).txSigningKey(this.cowKey);

        testRunner.start();

        Blockchain blockchain = testRunner.getBlockChain();

        Block blockAtHeightSix = blockchain.getBlockByNumber(7);
        assertEquals(BigInteger.valueOf(840L), testRunner.getAccountBalance(blockAtHeightSix.getCoinbase()));

        Block blockAtHeightSeven = blockchain.getBlockByNumber(8);
        assertEquals(BigInteger.valueOf(840L), testRunner.getAccountBalance(blockAtHeightSeven.getCoinbase()));

        Block blockAtHeightFiveMainchain = blockchain.getBlockByNumber(6);
        assertEquals(BigInteger.valueOf(5040L), testRunner.getAccountBalance(blockAtHeightFiveMainchain.getCoinbase()));

        Block blockAtHeightFiveFirstSibling = testRunner.getAddedSiblings().get(0);
        assertEquals(BigInteger.valueOf(5040L), testRunner.getAccountBalance(blockAtHeightFiveFirstSibling.getCoinbase()));

        Block blockAtHeightFiveSecondSibling = testRunner.getAddedSiblings().get(1);
        assertEquals(BigInteger.valueOf(4788L), testRunner.getAccountBalance(blockAtHeightFiveSecondSibling.getCoinbase()));

        this.validateRemascsStorageIsCorrect(this.getRemascStorageProvider(blockchain), BigInteger.valueOf(84000L), BigInteger.valueOf(252L), 0L);
    }

    @Test
    public void siblingIncludedSevenBlocksLater() throws IOException {

        BlockchainBuilder builder = new BlockchainBuilder().setBlockStore(new RemascTestBlockStore()).setTesting(true).setRsk(true).setGenesis(genesisBlock);

        List<SiblingElement> siblings = Lists.newArrayList(new SiblingElement(5, 12, this.minerFee));

        RemascTestRunner testRunner = new RemascTestRunner(builder, this.genesisBlock).txValue(txValue).minerFee(this.minerFee)
                .initialHeight(15).siblingElements(siblings).txSigningKey(this.cowKey);

        testRunner.start();

        Blockchain blockchain = testRunner.getBlockChain();

        Block blockAtHeightTwelve = blockchain.getBlockByNumber(13);
        assertEquals(BigInteger.valueOf(1680L), testRunner.getAccountBalance(blockAtHeightTwelve.getCoinbase()));

        Block blockAtHeightFiveMainchain = blockchain.getBlockByNumber(6);
        assertEquals(BigInteger.valueOf(7560L), testRunner.getAccountBalance(blockAtHeightFiveMainchain.getCoinbase()));

        Block blockAtHeightFiveSibling = testRunner.getAddedSiblings().get(0);
        assertEquals(BigInteger.valueOf(5292L), testRunner.getAccountBalance(blockAtHeightFiveSibling.getCoinbase()));

        BigInteger remascCurrentBalance = testRunner.getAccountBalance(Hex.decode(PrecompiledContracts.REMASC_ADDR));
        assertEquals(BigInteger.valueOf(296268L), remascCurrentBalance);

        this.validateRemascsStorageIsCorrect(this.getRemascStorageProvider(blockchain), BigInteger.valueOf(84000L), BigInteger.valueOf(2268L), 0L);
    }

    @Test
    public void siblingsFeeForMiningBlockMustBeRoundedAndTheRoundedSurplusBurned() throws IOException {

        BlockchainBuilder builder = new BlockchainBuilder().setBlockStore(new RemascTestBlockStore()).setTesting(true).setRsk(true).setGenesis(genesisBlock);
        long minerFee = 21000;

        List<SiblingElement> siblings = Lists.newArrayList();
        for (int i = 0; i < 9; i++) {
            siblings.add(new SiblingElement(5, 6, minerFee));
        }

        RemascTestRunner testRunner = new RemascTestRunner(builder, this.genesisBlock).txValue(txValue).minerFee(minerFee)
                .initialHeight(15).siblingElements(siblings).txSigningKey(this.cowKey);

        testRunner.start();

        Blockchain blockchain = testRunner.getBlockChain();

        Block blockAtHeightSix = blockchain.getBlockByNumber(7);
        assertEquals(BigInteger.valueOf(1674), testRunner.getAccountBalance(blockAtHeightSix.getCoinbase()));

        Block blockAtHeightSeven = blockchain.getBlockByNumber(8);
        assertNull(testRunner.getAccountBalance(blockAtHeightSeven.getCoinbase()));

        Block blockAtHeightFiveMainchain = blockchain.getBlockByNumber(6);
        assertEquals(BigInteger.valueOf(1512), testRunner.getAccountBalance(blockAtHeightFiveMainchain.getCoinbase()));

        Block blockAtHeightFiveFirstSibling = testRunner.getAddedSiblings().get(0);
        assertEquals(BigInteger.valueOf(1512), testRunner.getAccountBalance(blockAtHeightFiveFirstSibling.getCoinbase()));

        Block blockAtHeightFiveSecondSibling = testRunner.getAddedSiblings().get(1);
        assertEquals(BigInteger.valueOf(1512), testRunner.getAccountBalance(blockAtHeightFiveSecondSibling.getCoinbase()));

        this.validateRemascsStorageIsCorrect(this.getRemascStorageProvider(blockchain), BigInteger.valueOf(84000), BigInteger.valueOf(6), 0L);
    }

    @Test
    public void unclesPublishingFeeMustBeRoundedAndTheRoundedSurplusBurned() throws IOException {
        BlockchainBuilder builder = new BlockchainBuilder().setBlockStore(new RemascTestBlockStore()).setTesting(true).setRsk(true).setGenesis(genesisBlock);
        long minerFee = 21000;

        List<SiblingElement> siblings = Lists.newArrayList();
        for (int i = 0; i < 10; i++) {
            siblings.add(new SiblingElement(5, 6, minerFee));
        }

        RemascTestRunner testRunner = new RemascTestRunner(builder, this.genesisBlock).txValue(txValue).minerFee(minerFee)
                .initialHeight(15).siblingElements(siblings).txSigningKey(this.cowKey);

        testRunner.start();

        Blockchain blockchain = testRunner.getBlockChain();

        Block blockAtHeightSix = blockchain.getBlockByNumber(7);
        assertEquals(BigInteger.valueOf(1680), testRunner.getAccountBalance(blockAtHeightSix.getCoinbase()));

        Block blockAtHeightFiveMainchain = blockchain.getBlockByNumber(6);
        assertEquals(BigInteger.valueOf(1374), testRunner.getAccountBalance(blockAtHeightFiveMainchain.getCoinbase()));

        Block blockAtHeightFiveFirstSibling = testRunner.getAddedSiblings().get(0);
        assertEquals(BigInteger.valueOf(1374), testRunner.getAccountBalance(blockAtHeightFiveFirstSibling.getCoinbase()));

        Block blockAtHeightFiveSecondSibling = testRunner.getAddedSiblings().get(1);
        assertEquals(BigInteger.valueOf(1374), testRunner.getAccountBalance(blockAtHeightFiveSecondSibling.getCoinbase()));

        Block blockAtHeightFiveThirdSibling = testRunner.getAddedSiblings().get(2);
        assertEquals(BigInteger.valueOf(1374), testRunner.getAccountBalance(blockAtHeightFiveThirdSibling.getCoinbase()));

        this.validateRemascsStorageIsCorrect(this.getRemascStorageProvider(blockchain), BigInteger.valueOf(84000), BigInteger.valueOf(6), 0);
    }

    private List<Block> createSimpleBlocks(Block parent, int size) {
        List<Block> chain = new ArrayList<>();

        while (chain.size() < size) {
            Block newblock = RemascTestRunner.createBlock(this.genesisBlock, parent, PegTestUtils.createHash3(), PegTestUtils.createHash3(), null, null);
            chain.add(newblock);
            parent = newblock;
        }

        return chain;
    }

    private HashMap<byte[], BigInteger> getAccountsWithExpectedBalance(List<Long> otherAccountsBalance) {
        HashMap<byte[], BigInteger> accountsWithExpectedBalance = new HashMap<>();

        for(int currentAccount = 0; currentAccount < accountsAddressesUpToD.size(); currentAccount++)
            accountsWithExpectedBalance.put(accountsAddressesUpToD.get(currentAccount), otherAccountsBalance.get(currentAccount) == null ? null : BigInteger.valueOf(otherAccountsBalance.get(currentAccount)));

        return accountsWithExpectedBalance;
    }

    private void validateAccountsCurrentBalanceIsCorrect(Repository repository, BigInteger cowBalance, Long remascBalance, Long rskBalance, HashMap<byte[], BigInteger> otherAccountsBalance) {

        assertEquals(cowBalance, RemascTestRunner.getAccountBalance(repository, cowAddress));

        BigInteger remascExpectedBalance = BigInteger.valueOf(remascBalance);
        BigInteger remascActualBalance = RemascTestRunner.getAccountBalance(repository, Hex.decode(PrecompiledContracts.REMASC_ADDR));
        assertEquals(remascExpectedBalance, remascActualBalance);

        BigInteger rskExpectedBalance = rskBalance == null ? null : BigInteger.valueOf(rskBalance);
        assertEquals(rskExpectedBalance, RemascTestRunner.getAccountBalance(repository, remascConfig.getRskLabsAddress()));

        for(Map.Entry<byte[], BigInteger> entry : otherAccountsBalance.entrySet()) {
            BigInteger actualBalance = RemascTestRunner.getAccountBalance(repository, entry.getKey());
            assertEquals("Failed for: " + Hex.toHexString(entry.getKey()), entry.getValue(), actualBalance);
        }
    }

    private void validateRemascsStorageIsCorrect(RemascStorageProvider provider, BigInteger expectedRewardBalance, BigInteger expectedBurnedBalance, long expectedSiblingsSize) {
        assertEquals(expectedRewardBalance, provider.getRewardBalance());
        assertEquals(expectedBurnedBalance, provider.getBurnedBalance());
        assertEquals(expectedSiblingsSize, provider.getSiblings().size());
    }

    private RemascStorageProvider getRemascStorageProvider(Blockchain blockchain) throws IOException {
        return new RemascStorageProvider(blockchain.getRepository(), PrecompiledContracts.REMASC_ADDR);
    }
}
