/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.sdk.util;

import com.google.cloud.dataflow.sdk.transforms.windowing.BoundedWindow;
import com.google.cloud.dataflow.sdk.transforms.windowing.GlobalWindows;
import com.google.cloud.dataflow.sdk.transforms.windowing.Trigger;
import com.google.cloud.dataflow.sdk.values.CodedTupleTag;
import com.google.cloud.dataflow.sdk.values.CodedTupleTagMap;
import com.google.cloud.dataflow.sdk.values.PCollectionView;
import com.google.cloud.dataflow.sdk.values.TimestampedValue;
import com.google.cloud.dataflow.sdk.values.TupleTag;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;

import org.joda.time.Instant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@link ExecutionContext} for use in batch mode.
 */
public class BatchModeExecutionContext extends ExecutionContext {
  private Object key;
  private final Map<TupleTag<?>, Map<BoundedWindow, Object>> sideInputCache = new HashMap<>();

  /**
   * Create a new {@link ExecutionContext.StepContext}.
   */
  @Override
  public ExecutionContext.StepContext createStepContext(String stepName) {
    return new StepContext(stepName);
  }

  /**
   * Sets the key of the work currently being processed.
   */
  public void setKey(Object key) {
    this.key = key;
  }

  /**
   * Returns the key of the work currently being processed.
   *
   * <p> If there is not a currently defined key, returns null.
   */
  public Object getKey() {
    return key;
  }

  @Override
  public void setTimer(String timer, Instant timestamp, Trigger.TimeDomain domain) {
    throw new UnsupportedOperationException("setTimer is not supported in batch mode");
  }

  @Override
  public void deleteTimer(String timer, Trigger.TimeDomain domain) {
    throw new UnsupportedOperationException("deleteTimer is not supported in batch mode");
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T getSideInput(
      PCollectionView<T> view, BoundedWindow mainInputWindow, PTuple sideInputs) {
    TupleTag<Iterable<WindowedValue<?>>> tag = view.getTagInternal();
    Map<BoundedWindow, Object> tagCache = sideInputCache.get(tag);
    if (tagCache == null) {
      if (!sideInputs.has(tag)) {
        throw new IllegalArgumentException(
            "calling sideInput() with unknown view; did you forget to pass the view in "
            + "ParDo.withSideInputs()?");
      }
      tagCache = new HashMap<>();
      sideInputCache.put(tag, tagCache);
    }

    final BoundedWindow sideInputWindow =
        view.getWindowingStrategyInternal().getWindowFn().getSideInputWindow(mainInputWindow);

    // tagCache stores values in a type-safe way based on the TupleTag.
    T result = (T) tagCache.get(sideInputWindow);

    // TODO: Consider partial prefetching like in CoGBK to reduce iteration cost.
    if (result == null) {
      if (view.getWindowingStrategyInternal().getWindowFn() instanceof GlobalWindows) {
        result = view.fromIterableInternal(sideInputs.get(tag));
      } else {
        result = view.fromIterableInternal(
            Iterables.filter(sideInputs.get(tag),
                new Predicate<WindowedValue<?>>() {
                  @Override
                  public boolean apply(WindowedValue<?> element) {
                    return element.getWindows().contains(sideInputWindow);
                  }
                }));
      }
      tagCache.put(sideInputWindow, result);
    }

    return result;
  }

  /**
   * {@link ExecutionContext.StepContext} used in batch mode.
   */
  class StepContext extends ExecutionContext.StepContext {
    private Map<Object, Map<CodedTupleTag<?>, Object>> state = new HashMap<>();
    private Map<Object, Map<CodedTupleTag<?>, Map<Instant, List<TimestampedValue>>>> tagLists =
        new HashMap<>();

    StepContext(String stepName) {
      super(stepName);
    }

    @Override
    public <T> void store(CodedTupleTag<T> tag, T value) {
      Map<CodedTupleTag<?>, Object> perKeyState = state.get(getKey());
      if (perKeyState == null) {
        perKeyState = new HashMap<>();
        state.put(getKey(), perKeyState);
      }
      perKeyState.put(tag, value);
    }

    @Override
    public <T> void remove(CodedTupleTag<T> tag) {
      Map<CodedTupleTag<?>, Object> perKeyState = state.get(getKey());
      if (perKeyState != null) {
        perKeyState.remove(tag);
      }
    }

    @Override
    public CodedTupleTagMap lookup(List<? extends CodedTupleTag<?>> tags) {
      Map<CodedTupleTag<?>, Object> perKeyState = state.get(getKey());
      Map<CodedTupleTag<?>, Object> map = new HashMap<>();
      if (perKeyState != null) {
        for (CodedTupleTag<?> tag : tags) {
          map.put(tag, perKeyState.get(tag));
        }
      }
      return CodedTupleTagMap.of(map);
    }

    @Override
    public <T> void writeToTagList(CodedTupleTag<T> tag, T value, Instant timestamp) {
      Map<CodedTupleTag<?>, Map<Instant, List<TimestampedValue>>> perKeyTagLists =
          tagLists.get(getKey());
      if (perKeyTagLists == null) {
        perKeyTagLists = new HashMap<>();
        tagLists.put(getKey(), perKeyTagLists);
      }
      Map<Instant, List<TimestampedValue>> tagList = perKeyTagLists.get(tag);
      if (tagList == null) {
        tagList = new TreeMap<>();
        perKeyTagLists.put(tag, tagList);
      }
      List<TimestampedValue> timestampList = tagList.get(timestamp);
      if (timestampList == null) {
        timestampList = new ArrayList<>();
        tagList.put(timestamp, timestampList);
      }
      timestampList.add(TimestampedValue.of(value, timestamp));
    }

    @Override
    public <T> void deleteTagList(CodedTupleTag<T> tag) {
      Map<CodedTupleTag<?>, Map<Instant, List<TimestampedValue>>> perKeyTagLists =
          tagLists.get(getKey());
      if (perKeyTagLists != null) {
        perKeyTagLists.remove(tag);
      }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Iterable<TimestampedValue<T>> readTagList(CodedTupleTag<T> tag) {
      Map<CodedTupleTag<?>, Map<Instant, List<TimestampedValue>>> perKeyTagLists =
          tagLists.get(getKey());
      if (perKeyTagLists == null || perKeyTagLists.get(tag) == null) {
        return new ArrayList<TimestampedValue<T>>();
      }
      return Iterables.concat((Iterable) perKeyTagLists.get(tag).values());
    }
  }
}
