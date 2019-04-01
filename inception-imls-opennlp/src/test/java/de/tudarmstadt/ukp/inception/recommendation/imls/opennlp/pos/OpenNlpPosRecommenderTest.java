/*
 * Copyright 2018
 * Ubiquitous Knowledge Processing (UKP) Lab
 * Technische Universität Darmstadt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.inception.recommendation.imls.opennlp.pos;

import static java.util.Arrays.asList;
import static org.apache.uima.fit.factory.CollectionReaderFactory.createReader;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.uima.UIMAException;
import org.apache.uima.cas.CAS;
import org.apache.uima.collection.CollectionReader;
import org.apache.uima.fit.factory.JCasFactory;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.junit.Before;
import org.junit.Test;

import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationFeature;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationLayer;
import de.tudarmstadt.ukp.dkpro.core.api.datasets.Dataset;
import de.tudarmstadt.ukp.dkpro.core.api.datasets.DatasetFactory;
import de.tudarmstadt.ukp.dkpro.core.api.lexmorph.type.pos.POS;
import de.tudarmstadt.ukp.dkpro.core.io.conll.Conll2006Reader;
import de.tudarmstadt.ukp.dkpro.core.testing.DkproTestContext;
import de.tudarmstadt.ukp.inception.recommendation.api.evaluation.DataSplitter;
import de.tudarmstadt.ukp.inception.recommendation.api.evaluation.IncrementalSplitter;
import de.tudarmstadt.ukp.inception.recommendation.api.evaluation.PercentageBasedSplitter;
import de.tudarmstadt.ukp.inception.recommendation.api.model.Recommender;
import de.tudarmstadt.ukp.inception.recommendation.api.recommender.RecommenderContext;
import de.tudarmstadt.ukp.inception.recommendation.api.type.PredictedSpan;

public class OpenNlpPosRecommenderTest
{
    private static File cache = DkproTestContext.getCacheFolder();
    private static DatasetFactory loader = new DatasetFactory(cache);

    private RecommenderContext context;
    private Recommender recommender;
    private OpenNlpPosRecommenderTraits traits;

    @Before
    public void setUp()
    {
        context = new RecommenderContext();
        recommender = buildRecommender();
        traits = new OpenNlpPosRecommenderTraits();
        traits.setNumThreads(2);
        traits.setTrainingSetSizeLimit(250);
        traits.setPredictionLimit(250);
    }

    @Test
    public void thatTrainingWorks() throws Exception
    {
        OpenNlpPosRecommender sut = new OpenNlpPosRecommender(recommender, traits);
        List<CAS> casList = loadDevelopmentData();

        sut.train(context, casList);

        assertThat(context.get(OpenNlpPosRecommender.KEY_MODEL))
            .as("Model has been set")
            .isPresent();
    }

    @Test
    public void thatPredictionWorks() throws Exception
    {
        OpenNlpPosRecommender sut = new OpenNlpPosRecommender(recommender, traits);
        List<CAS> casList = loadDevelopmentData();
        
        CAS cas = casList.get(0);
        
        sut.train(context, asList(cas));

        sut.predict(context, cas);

        Collection<PredictedSpan> predictions = JCasUtil.select(cas.getJCas(), PredictedSpan.class);

        assertThat(predictions).as("Predictions have been written to CAS")
            .isNotEmpty();
    }

    @Test
    public void thatEvaluationWorks() throws Exception
    {
        DataSplitter splitStrategy = new PercentageBasedSplitter(0.8, 10);
        OpenNlpPosRecommender sut = new OpenNlpPosRecommender(recommender, traits);
        List<CAS> casList = loadDevelopmentData();

        double score = sut.evaluate(casList, splitStrategy).computeF1Score();

        assertThat(score).isStrictlyBetween(0.0, 1.0);
    }

    @Test
    public void thatIncrementalPosEvaluationWorks() throws Exception
    {
        IncrementalSplitter splitStrategy = new IncrementalSplitter(0.8, 250, 10);
        OpenNlpPosRecommender sut = new OpenNlpPosRecommender(recommender, traits);
        List<CAS> casList = loadAllData();

        int i = 0;
        while (splitStrategy.hasNext() && i < 3) {
            splitStrategy.next();
            
            double score = sut.evaluate(casList, splitStrategy).computeF1Score();

            assertThat(score).isBetween(0.0, 1.0);
            
            i++;
        }
    }

    private List<CAS> loadAllData() throws IOException, UIMAException
    {
        Dataset ds = loader.load("gum-en-conll-3.0.0");
        return loadData(ds, ds.getDataFiles());
    }

    private List<CAS> loadDevelopmentData() throws IOException, UIMAException
    {
        Dataset ds = loader.load("gum-en-conll-3.0.0");
        return loadData(ds, ds.getSplit(0.2).getTrainingFiles());
    }

    private List<CAS> loadData(Dataset ds, File ... files) throws UIMAException, IOException
    {
        CollectionReader reader = createReader(Conll2006Reader.class,
            Conll2006Reader.PARAM_PATTERNS, files,
            Conll2006Reader.PARAM_LANGUAGE, ds.getLanguage());

        List<CAS> casList = new ArrayList<>();
        while (reader.hasNext()) {
            JCas cas = JCasFactory.createJCas();
            reader.getNext(cas.getCas());
            casList.add(cas.getCas());
        }
        return casList;
    }

    private static Recommender buildRecommender()
    {
        AnnotationLayer layer = new AnnotationLayer();
        layer.setName(POS.class.getName());

        AnnotationFeature feature = new AnnotationFeature();
        feature.setName("PosValue");
        
        Recommender recommender = new Recommender();
        recommender.setLayer(layer);
        recommender.setFeature(feature);
        recommender.setMaxRecommendations(3);

        return recommender;
    }
}
