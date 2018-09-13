/*
 * Copyright 2016-2018 shardingsphere.io.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package io.shardingsphere.core.event.executor;

import io.shardingsphere.core.event.ShardingEvent;
import io.shardingsphere.core.routing.RouteUnit;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * SQL execution event.
 * 
 * @author gaohongtao
 * @author maxiaoguang
 */
@Getter
public class SQLExecutionStartEvent extends SQLExecutionEvent {
    
    private final String url;
    
    public SQLExecutionStartEvent(final RouteUnit routeUnit, final List<Object> parameters, final String url) {
        super(routeUnit, parameters);
        this.url = url;
    }
}
