package it.davidlab.service;

import com.algorand.algosdk.account.Account;
import com.algorand.algosdk.crypto.Address;
import com.algorand.algosdk.transaction.SignedTransaction;
import com.algorand.algosdk.util.Encoder;
import com.algorand.algosdk.v2.client.common.AlgodClient;
import com.algorand.algosdk.v2.client.common.IndexerClient;
import com.algorand.algosdk.v2.client.common.Response;
import com.algorand.algosdk.v2.client.model.*;
import it.davidlab.model.AssetModel;
import it.davidlab.model.Metadata;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;


public class AlgoService {

    private AlgodClient algodClient;
    private IndexerClient indexerClient;
    private Account algoAccount;
    private Address algoAddress;

    public AlgoService(String algodApiAddr, Integer algodPort, String algodApiToken,
                       String indexerApiAddr, Integer indexerApiPort,
                       String accPassphrase, String accAddress) throws GeneralSecurityException {


        algoAccount = new Account(accPassphrase);
        algoAddress = new Address(accAddress);

        algodClient = new AlgodClient(algodApiAddr, algodPort, algodApiToken);
        indexerClient = new IndexerClient(indexerApiAddr, indexerApiPort);

    }


    public void sendAlgo(com.algorand.algosdk.account.Account receiverAccount, long amount) throws Exception {

        long mAlgoAmount = amount * 1000000L; //converted in microAlgorand

        String note = "AlgoServerless Test";
        TransactionParametersResponse params = algodClient.TransactionParams().execute().body();
        com.algorand.algosdk.transaction.Transaction tx =
                com.algorand.algosdk.transaction.Transaction.PaymentTransactionBuilder()
                        .sender(algoAddress)
                        .note(note.getBytes())
                        .amount(mAlgoAmount)
                        .receiver(receiverAccount.getAddress())
                        .suggestedParams(params)
                        .build();

        SignedTransaction signedTx = algoAccount.signTransaction(tx);
        //TODO check execute
        Response<PostTransactionsResponse> txResponse =
                algodClient.RawTransaction().rawtxn(Encoder.encodeToMsgPack(signedTx)).execute();

        String txId;
        if (txResponse.isSuccessful()) {
            txId = txResponse.body().txId;
            // write transaction to node
            waitForConfirmation(txId, 6);
        } else {
            throw new Exception("Transaction Error");
        }

    }


    public void waitForConfirmation(String txId, int timeout) throws Exception {

        Long txConfirmedRound = -1L;
        Response<NodeStatusResponse> statusResponse = algodClient.GetStatus().execute();

        long lastRound;
        if (statusResponse.isSuccessful()) {
            lastRound = statusResponse.body().lastRound + 1L;
        }
        else {
            throw new IllegalStateException("Cannot get node status");
        }

        long maxRound = lastRound + timeout;

        for (long currentRound = lastRound; currentRound < maxRound; currentRound++) {
            Response<PendingTransactionResponse> response = algodClient.PendingTransactionInformation(txId).execute();

            if (response.isSuccessful()) {
                txConfirmedRound = response.body().confirmedRound;
                if (txConfirmedRound == null) {
                    if (!algodClient.WaitForBlock(currentRound).execute().isSuccessful()) {
                        throw new Exception();
                    }
                }
                else {
                    return;
                }
            } else {
                throw new IllegalStateException("The transaction has been rejected");
            }
        }

        throw new IllegalStateException("Transaction not confirmed after " + timeout + " rounds!");
    }


    public Optional<Long> getAccountAmount() {

        Response<com.algorand.algosdk.v2.client.model.Account> accountResponse;
        try {
            accountResponse = algodClient.AccountInformation(algoAccount.getAddress()).execute();
        }
        catch (Exception e) {
            return Optional.empty();
        }

        if (accountResponse.isSuccessful()) {
            return Optional.of(accountResponse.body().amount);
        }
        else {
            return Optional.empty();
        }

    }

    public AssetModel createAsset(AssetModel assetModel) throws Exception{

        Response<TransactionParametersResponse> txParResp = algodClient.TransactionParams().execute();

        TransactionParametersResponse txParams;
        if (txParResp.isSuccessful()) {
            txParams = txParResp.body();
        }
        else {
            throw new Exception("Cannot get tx parameters");
        }

        byte[] encAssetProps = Encoder.encodeToMsgPack(assetModel.getMetadata());

        com.algorand.algosdk.transaction.Transaction txTicket = com.algorand.algosdk.transaction.Transaction.AssetCreateTransactionBuilder()
                .sender(algoAddress)
                .assetTotal(assetModel.getAssetTotal())
                .assetDecimals(assetModel.getAssetDecimals())
                .assetUnitName(assetModel.getUnitName())
                .assetName(assetModel.getAssetName())
                .url(assetModel.getUrl())
                .manager(algoAddress)
                .reserve(algoAddress)
                .freeze(algoAddress)
                .clawback(algoAddress)
                .defaultFrozen(assetModel.isDefaultFrozen())
                .note(encAssetProps)
//???                .metadataHash(propsHashR)
                .suggestedParams(txParams)
                .build();

        // Set the tx Fees
        BigInteger origfee = BigInteger.valueOf(txParams.fee);
        Account.setFeeByFeePerByte(txTicket, origfee);

        SignedTransaction signedTx = algoAccount.signTransaction(txTicket);
        byte[] encodedTxBytes = Encoder.encodeToMsgPack(signedTx);

        Response<PostTransactionsResponse> txResponse =
                algodClient.RawTransaction().rawtxn(encodedTxBytes).execute();

        String txId;

        if (txResponse.isSuccessful()) {
            txId = txResponse.body().txId;
            waitForConfirmation(txId, 6);

            assetModel.setTxId(txId);
            return assetModel;

        } else {
            throw new Exception("Transaction Failed");
        }
    }

    public Optional<Metadata> getAssetParams(long assetId) {

        Optional<Metadata> metadataOpt = Optional.empty();

        // search for the ACFG transactions
        Response<TransactionsResponse> txResponse;
        try {
            txResponse = indexerClient.searchForTransactions()
                    .address(algoAddress).addressRole(Enums.AddressRole.SENDER)
                    .assetId(assetId).txType(Enums.TxType.ACFG).execute();
        } catch (Exception e) {
            return metadataOpt;
        }

        if (txResponse.isSuccessful()) {
            List<Transaction> txs = txResponse.body().transactions;

            // get the last note field not null
            byte[] note = txs.stream().min(Comparator.comparingLong(t -> t.confirmedRound))
                    .map(transaction -> transaction.note).orElse(null);

            if (note != null) {
                try {
                    metadataOpt = Optional.of(Encoder.decodeFromMsgPack(note, Metadata.class));
                } catch (IOException e) {

                }
            }
        }
        return metadataOpt;
    }

}
