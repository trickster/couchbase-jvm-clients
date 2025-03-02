/*
 * Copyright (c) 2020 Couchbase, Inc.
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

package com.couchbase.client.core.cnc.metrics;

import com.couchbase.client.core.annotation.Stability;
import com.couchbase.client.core.cnc.Counter;
import com.couchbase.client.core.cnc.EventBus;
import com.couchbase.client.core.cnc.Meter;
import com.couchbase.client.core.cnc.TracingIdentifiers;
import com.couchbase.client.core.cnc.ValueRecorder;
import com.couchbase.client.core.cnc.events.metrics.LatencyMetricsAggregatedEvent;
import com.couchbase.client.core.deps.org.HdrHistogram.Histogram;
import com.couchbase.client.core.env.AggregatingMeterConfig;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The default metrics implementation which aggregates latency information and emits it at a regular interval.
 */
@Stability.Volatile
public class AggregatingMeter implements Meter {

  private static final AtomicInteger METER_ID = new AtomicInteger();

  private final EventBus eventBus;
  private final Thread worker;
  private final AtomicBoolean running = new AtomicBoolean(false);

  private final Map<NameAndTags, AggregatingValueRecorder> valueRecorders = new ConcurrentHashMap<>();

  private final long emitIntervalMs;

  public static AggregatingMeter create(EventBus eventBus, AggregatingMeterConfig config) {
    return new AggregatingMeter(config, eventBus);
  }

  private AggregatingMeter(AggregatingMeterConfig config, EventBus eventBus) {
    this.eventBus = eventBus;
    this.emitIntervalMs = config.emitInterval().toMillis();

    worker = new Thread(new Worker());
    worker.setDaemon(true);
  }

  /**
   * Note that since we are not performing any aggregations on the counter type, this pretty much
   * is a NOOP for performance reasons.
   *
   * @param name the name of the counter (unused).
   * @param tags the tags to apply (unused).
   * @return a cached noop instance of the counter.
   */
  @Override
  public Counter counter(String name, Map<String, String> tags) {
    return AggregatingCounter.INSTANCE;
  }

  @Override
  public synchronized ValueRecorder valueRecorder(String name, Map<String, String> tags) {
    return valueRecorders.computeIfAbsent(
      new NameAndTags(name, tags),
      key -> new AggregatingValueRecorder(name, tags)
    );
  }

  @Override
  public Mono<Void> start() {
    return Mono.defer(() -> {
      if (running.compareAndSet(false, true)) {
        worker.start();
      }
      return Mono.empty();
    });
  }

  @Override
  public Mono<Void> stop(Duration timeout) {
    return Mono.defer(() -> {
      if (running.compareAndSet(true, false)) {
        worker.interrupt();
      }
      return Mono.empty();
    });
  }

  private class Worker implements Runnable {

    @Override
    public void run() {
      Thread.currentThread().setName("cb-metrics-" + METER_ID.incrementAndGet());

      while (running.get()) {
        try {
          Thread.sleep(emitIntervalMs);
          dumpMetrics();
        } catch (final InterruptedException ex) {
          if (!running.get()) {
            return;
          } else {
            Thread.currentThread().interrupt();
          }
        } catch (final Exception ex) {
          // TODO: raise event
          // LOGGER.warn("Got exception on slow operation reporter, ignoring.", ex);
        }
      }
    }

    @SuppressWarnings("unchecked")
    private synchronized void dumpMetrics() {
      Map<String,  Map<String, Object>> output = new HashMap<>();

      Map<String, Object> meta = new HashMap<>();
      meta.put("emit_interval_s", TimeUnit.MILLISECONDS.toSeconds(emitIntervalMs));
      output.put("meta", meta);

      boolean wroteRow = false;

      for (Map.Entry<NameAndTags, AggregatingValueRecorder> entry : valueRecorders.entrySet()) {
        if (!entry.getKey().name().equals(TracingIdentifiers.METER_REQUESTS)) {
          continue;
        }

        AggregatingValueRecorder avr = entry.getValue();
        Histogram histogram = avr.clearStats();
        if (histogram.getTotalCount() == 0) {
          continue;
        }
        wroteRow = true;

        String service = avr.tags().get(TracingIdentifiers.ATTR_SERVICE);
        String hostname = avr.tags().get(TracingIdentifiers.ATTR_REMOTE_HOSTNAME);

        Map<String, Object> serviceMap = output.computeIfAbsent(service, k -> new HashMap<>());
        Map<String, Object> hostMap = (Map<String, Object>) serviceMap.computeIfAbsent(hostname, k -> new HashMap<>());

        hostMap.put("total_count", histogram.getTotalCount());

        Map<String, Object> percentiles = new LinkedHashMap<>();
        percentiles.put("50.0", histogram.getValueAtPercentile(50.0) / 1000.0);
        percentiles.put("90.0", histogram.getValueAtPercentile(90.0) / 1000.0);
        percentiles.put("99.0", histogram.getValueAtPercentile(99.0) / 1000.0);
        percentiles.put("99.9", histogram.getValueAtPercentile(99.9) / 1000.0);
        percentiles.put("100.0", histogram.getMaxValue() / 1000.0);

        hostMap.put("percentiles_us", percentiles);
      }

      if (wroteRow) {
        eventBus.publish(new LatencyMetricsAggregatedEvent(Duration.ofMillis(emitIntervalMs), output));
      }
    }
  }


}
