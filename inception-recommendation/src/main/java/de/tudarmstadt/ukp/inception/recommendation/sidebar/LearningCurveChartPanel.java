/*
 * Copyright 2017
 * Ubiquitous Knowledge Processing (UKP) Lab
 * Technische Universität Darmstadt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.inception.recommendation.sidebar;

import static de.tudarmstadt.ukp.clarin.webanno.support.JSONUtil.fromJsonString;
import static de.tudarmstadt.ukp.clarin.webanno.support.JSONUtil.toJsonString;
import static org.apache.commons.lang3.StringUtils.substring;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.wicket.feedback.IFeedback;
import org.apache.wicket.markup.head.CssHeaderItem;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.head.JavaScriptHeaderItem;
import org.apache.wicket.markup.html.WebComponent;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.spring.injection.annot.SpringBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wicketstuff.event.annotation.OnEvent;

import de.agilecoders.wicket.webjars.request.resource.WebjarsCssResourceReference;
import de.agilecoders.wicket.webjars.request.resource.WebjarsJavaScriptResourceReference;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.model.AnnotatorState;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.rendering.event.RenderAnnotationsEvent;
import de.tudarmstadt.ukp.inception.log.EventRepository;
import de.tudarmstadt.ukp.inception.log.model.LoggedEvent;
import de.tudarmstadt.ukp.inception.recommendation.log.RecommenderEvaluationResultEventAdapter.Details;

public class LearningCurveChartPanel
    extends Panel
{
    private static final long serialVersionUID = 4306746527837380863L;

    private static final String CHART_CONTAINER = "chart-container";

    private Logger log = LoggerFactory.getLogger(getClass());
    private WebComponent chartContainer;

    private @SpringBean EventRepository eventRepo;

    IModel<AnnotatorState> model;

    int maxPointsToPlot = 50;

    public LearningCurveChartPanel(String aId, IModel<AnnotatorState> aModel)
    {
        super(aId);
        this.model = aModel;

        chartContainer = new Label(CHART_CONTAINER);
        chartContainer.setOutputMarkupId(true);
        add(chartContainer);
    }

    @Override
    public void renderHead(IHeaderResponse aResponse)
    {
        super.renderHead(aResponse);

        // import Js
        aResponse.render(JavaScriptHeaderItem
                .forReference(new WebjarsJavaScriptResourceReference("c3/current/c3.js")));
        aResponse.render(JavaScriptHeaderItem
                .forReference(new WebjarsJavaScriptResourceReference("d3js/current/d3.js")));

        // import Css
        aResponse.render(
                CssHeaderItem.forReference(new WebjarsCssResourceReference("c3/current/c3.css")));
    }

    @OnEvent
    public void onRenderAnnotations(RenderAnnotationsEvent aEvent)
    {
        log.info("rendered annotation event");

        HashMap<String, List<Double>> recommenderScoreMap = getLatestScores(aEvent);

        if (recommenderScoreMap == null || recommenderScoreMap.isEmpty()) {
            log.error("No evaluation data for the learning curve. Project: {}",
                    model.getObject().getProject());

            error("Cannot plot the learning curve. Please make some annotations");
            aEvent.getRequestHandler().addChildren(getPage(), IFeedback.class);

            return;
        }

        // iterate over the recommender score map to create data arrays to feed to the
        // c3 graph
        StringBuilder dataColumns = new StringBuilder();
        StringBuilder chartType = new StringBuilder();

        recommenderScoreMap.forEach((key, value) -> {

            ArrayList<Double> listScores = (ArrayList<Double>) value;

            // should not happen ideally
            if (listScores == null || listScores.size() <= 0) {
                return;
            }

            // convert array to a comma separated string
            String data = listScores.stream().map(Object::toString)
                    .collect(Collectors.joining(", "));

            // append recommender name to the data
            dataColumns.append("['");
            String[] recommenderClass = key.toString().split("\\.");
            String recommenderName = recommenderClass[recommenderClass.length - 1];

            // define chart type for the recommender
            chartType.append(recommenderName);
            chartType.append(": 'step', ");

            dataColumns.append(recommenderName);

            // append data columns
            dataColumns.append("', ");
            dataColumns.append(data);
            dataColumns.append("]");
            dataColumns.append(",");
        });

        // should not happen ideally
        if (dataColumns.length() <= 0 || chartType.length() <= 0) {
            return;
        }

        try {
            String javascript = createJSScript(dataColumns.toString(), chartType.toString());
            log.debug("Rendering Recommender Evaluation Chart: {}", javascript);

            aEvent.getRequestHandler().prependJavaScript(javascript);
        }
        catch (IOException e) {
            log.error("Unable to render chart", e);
            error("Unable to render chart: " + e.getMessage());
            aEvent.getRequestHandler().addChildren(getPage(), IFeedback.class);
        }
    }

    /**
     * Creates the js script to render graph with the help of given data points. Sample value of
     * dataColumns: ['recommender1', 1.0, 2.0, 3.0 ], ['recommender2', 2.0, 3.0, 4.0]. Also creates
     * an xaxix of a sequence from 0 to maximumNumberOfPoints (50)
     * 
     * @param aDataColumns
     * @param aChartType
     * @return
     * @throws IOException
     */
    private String createJSScript(String aDataColumns, String aChartType) throws IOException
    {
        int[] intArray = IntStream.range(0, maxPointsToPlot).map(i -> i).toArray();

        String xaxisValues = "[ 'x' ," + substring(Arrays.toString(intArray), 1, -1) + "]";
        String data = toJsonString(aDataColumns).substring(1, aDataColumns.toString().length());

        // bind data to chart container
        String javascript = "var chart=c3.generate({bindto:'#" + chartContainer.getMarkupId()
                + "',data:{ x:'x', columns:[" + xaxisValues + " ," + data + "],types:{" + aChartType
                + "}}});;";
        return javascript;
    }

    /**
     * Fetches a number of latest evaluation scores from the database and save it in the map
     * corresponding to each recommender for which the scores have been logged in the database
     */
    private HashMap<String, List<Double>> getLatestScores(RenderAnnotationsEvent aEvent)
    {
        // we want to plot RecommenderEvaluationResultEvent for the learning curve. The
        // value of the event
        String eventType = "RecommenderEvaluationResultEvent";

        List<LoggedEvent> loggedEvents = eventRepo.listLoggedEvents(model.getObject().getProject(),
                model.getObject().getUser().getUsername(), eventType, maxPointsToPlot);

        if (loggedEvents == null || loggedEvents.isEmpty()) {
            return null;
        }

        // we want to show the latest record on the right side of the graph
        Collections.reverse(loggedEvents);

        HashMap<String, List<Double>> recommenderScoreMap = new HashMap<>();

        // iterate over the logged events to extract the scores and map
        // it against its corresponding recommender.
        for (LoggedEvent loggedEvent : loggedEvents) {
            String detailJson = loggedEvent.getDetails();
            try {
                Details detail = fromJsonString(Details.class, detailJson);
                List<Double> list = recommenderScoreMap.get(detail.tool);
                if (list == null) {
                    list = new ArrayList<Double>();
                }

                addScoreToMap(recommenderScoreMap, detail.score, detail.tool, list);

            }
            catch (IOException e) {
                log.error("Invalid logged Event detail. Skipping record with logged event id: "
                        + loggedEvent.getId(), e);

                error("Invalid logged Event detail. Skipping record with logged event id: "
                        + loggedEvent.getId());

                aEvent.getRequestHandler().addChildren(getPage(), IFeedback.class);
            }
        }
        return recommenderScoreMap;
    }

    /**
     * adds the score to the given map, if the value of the score is valid/finite
     * 
     * @param aRecommenderScoreMap
     * @param aScore
     * @param tool
     * @param list
     */
    private void addScoreToMap(HashMap<String, List<Double>> aRecommenderScoreMap, Double aScore,
            String tool, List<Double> list)
    {
        // sometimes score values NaN. Can result into error while rendering the graph on UI
        if (!Double.isFinite(aScore)) {
            return;
        }

        list.add(aScore);
        aRecommenderScoreMap.put(tool, list);
    }
}
