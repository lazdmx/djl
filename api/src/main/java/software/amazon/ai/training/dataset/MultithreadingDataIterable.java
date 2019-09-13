/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package software.amazon.ai.training.dataset;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.ai.Context;
import software.amazon.ai.ndarray.NDList;
import software.amazon.ai.training.Trainer;
import software.amazon.ai.translate.TranslatorContext;
import software.amazon.ai.util.Pair;

public class MultithreadingDataIterable<I, L> implements Iterable<Batch> {
    private RandomAccessDataset<I, L> dataset;
    private Trainer<I, L, ?> trainer;
    private Sampler sampler;
    private DataLoadingConfiguration config;
    private static final Logger logger = LoggerFactory.getLogger(MultithreadingDataIterable.class);

    public MultithreadingDataIterable(
            RandomAccessDataset<I, L> dataset,
            Trainer<I, L, ?> trainer,
            Sampler sampler,
            DataLoadingConfiguration config) {
        this.dataset = dataset;
        this.trainer = trainer;
        this.sampler = sampler;
        this.config = config;
    }

    @Override
    public Iterator<Batch> iterator() {
        return new MultithreadingDataIterator<>(dataset, trainer, sampler, config);
    }

    private static class MultithreadingDataIterator<I, L> implements Iterator<Batch> {
        private RandomAccessDataset<I, L> dataset;
        private Trainer<I, L, ?> trainer;
        private Iterator<List<Long>> sample;
        private Batchifier batchifier;
        private Context pinDeviceContext;
        private ExecutorService executor;
        private Queue<Future<Batch>> queue;

        public MultithreadingDataIterator(
                RandomAccessDataset<I, L> dataset,
                Trainer<I, L, ?> trainer,
                Sampler sampler,
                DataLoadingConfiguration config) {
            this.dataset = dataset;
            this.trainer = trainer;
            this.sample = sampler.sample(trainer, dataset);
            this.batchifier = config.getBatchifier();
            this.pinDeviceContext = config.getPinDeviceContext();
            this.executor = config.getExecutor();
            this.queue = new LinkedList<>();

            if (batchifier == null) {
                // default batchifier is StackBatchifier
                batchifier = new StackBatchifier();
            }

            // prefetch
            for (int i = 0; i < ((ThreadPoolExecutor) executor).getCorePoolSize() * 2; i++) {
                preFetch();
            }
        }

        @Override
        public boolean hasNext() {
            return !queue.isEmpty();
        }

        @Override
        public Batch next() {
            preFetch();
            Future<Batch> future = queue.poll();
            try {
                return future.get();
            } catch (InterruptedException | ExecutionException e) {
                logger.error(e.getMessage());
            }
            throw new IllegalStateException("Data loading failed");
        }

        private void preFetch() {
            List<Long> indices;
            if (sample.hasNext()) {
                indices = sample.next();
            } else {
                return;
            }
            Callable<Batch> task = new PreFetchCallable(indices);
            Future<Batch> result = executor.submit(task);
            queue.offer(result);
        }

        class PreFetchCallable implements Callable<Batch> {
            private List<Long> indices;

            public PreFetchCallable(List<Long> indices) {
                this.indices = indices;
            }

            @Override
            public Batch call() {
                NDList[] data = new NDList[indices.size()];
                NDList[] labels = new NDList[indices.size()];
                TranslatorContext ctx = trainer.getPreprocessContext();
                for (int i = 0; i < indices.size(); i++) {
                    Pair<I, L> dataItem = dataset.get(indices.get(i));
                    Record record;
                    try {
                        record = trainer.getTranslator().processInput(ctx, dataItem);
                    } catch (Exception e) {
                        throw new IllegalStateException("Failed to get next data item", e);
                    }
                    data[i] = record.getData();
                    labels[i] = record.getLabels();
                }
                NDList batchData = batchifier.batchify(data);
                NDList batchLabels = batchifier.batchify(labels);
                // pin the device to specific context
                if (pinDeviceContext != null) {
                    batchData = batchData.asInContext(pinDeviceContext, false);
                    batchLabels = batchLabels.asInContext(pinDeviceContext, false);
                }
                Batch batch = new Batch(trainer.getManager(), batchData, batchLabels);
                ctx.close();
                return batch;
            }
        }
    }
}