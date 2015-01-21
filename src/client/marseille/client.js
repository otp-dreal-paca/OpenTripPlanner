/* This program is free software: you can redistribute it and/or
   modify it under the terms of the GNU Lesser General Public License
   as published by the Free Software Foundation, either version 3 of
   the License, or (at your option) any later version.
   
   This program is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.
   
   You should have received a copy of the GNU General Public License
   along with this program.  If not, see <http://www.gnu.org/licenses/>. 
 */

/*
 * OpenTripPlanner Analyst JavaScript library demo (Marseille Metropole).
 */
$(function() {

    /* Global context object. */
    var gui = {};
    /* Our reference point for diff mode */
    gui.GRID_ORIGIN = L.latLng(43.3, 5.4);

    /* Initialize a leaflet map */
    gui.map = L.map('map', {
        minZoom : 10,
        maxZoom : 18,
    }).setView(L.latLng(43.297, 5.370), 11);

    /* Add OSM/OpenTransport layers. TODO Add MapBox layer. */
    gui.osmLayer = new L.TileLayer("http://{s}.mqcdn.com/tiles/1.0.0/osm/{z}/{x}/{y}.png", {
        subdomains : [ "otile1", "otile2", "otile3", "otile4" ],
        maxZoom : 18,
        attribution : "Map data &copy; OpenStreetMap contributors"
    });
    gui.otLayer = new L.TileLayer(
            "http://{s}.tile.thunderforest.com/transport/{z}/{x}/{y}.png",
            {
                subdomains : [ "a", "b", "c" ],
                maxZoom : 18,
                attribution : "Map data &copy; OpenStreetMap contributors & <a href='http://www.thunderforest.com/'>Thunderforest</a>"
            });
    gui.map.addLayer(gui.otLayer);

    /* Create 3 layer groups for easier display / management */
    gui.gradientLayerGroup = new L.LayerGroup([]);
    gui.isochronesLayerGroup = new L.LayerGroup([]);
    gui.populationLayerGroup = new L.LayerGroup([]);
    gui.map.addLayer(gui.gradientLayerGroup);
    gui.map.addLayer(gui.isochronesLayerGroup);
    gui.map.addLayer(gui.populationLayerGroup);

    /* Add controls to the map */
    L.control.layers({
        "Transport" : gui.otLayer,
        "OSM" : gui.osmLayer
    }, {
        "Points d'intérêts" : gui.populationLayerGroup,
        "Temps de trajet" : gui.gradientLayerGroup
    /* "Isochrones" : gui.isochronesLayerGroup */
    }).addTo(gui.map);

    /* Custom info (transparency control) */
    var mapinfo = L.control();
    mapinfo.onAdd = function(map) {
        this._div = L.DomUtil.create('div', 'mapinfo');
        $('<div/>', {
            text : 'Transparence'
        }).appendTo(this._div);
        $('<div/>').slider({
            max : 1.0,
            step : 0.1,
            value : 0.5,
            slide : function(event, ui) {
                gui.opacity = ui.value;
                if (gui.layer)
                    gui.layer.setOpacity(gui.opacity);
            }
        }).appendTo(this._div);
        return this._div;
    };
    gui.opacity = 0.5
    mapinfo.addTo(gui.map);

    /* Legend */
    var maplegend = L.control({
        position : 'bottomright'
    });
    maplegend.onAdd = function(map) {
        this._div = L.DomUtil.create('div', 'maplegend');
        var popLegend = $('<div/>', {
            id : 'legendPop',
        }).appendTo(this._div);
        popLegend.append("<span class='circleMarker'/><span id='legendPopLabel'>")
        $('<div/>', {
            id : 'legendHeader',
            text : 'Couleur selon la durée du trajet'
        }).appendTo(this._div);
        // HACK ALERT - Do NOT change the case of Width and Height below!
        $('<canvas/>', {
            id : 'legend',
            Width : 300,
            Height : 16
        }).appendTo(this._div);
        return this._div;
    };
    maplegend.addTo(gui.map);

    /* Select client-wide locale */
    otp.setLocale(otp.locale.French);

    /* Create a request parameter widget */
    gui.widget1 = new otp.analyst.ParamsWidget($('#widget1'), {
        coordinateOrigin : gui.GRID_ORIGIN,
        selectMaxTime : true,
        map : gui.map
    });

    /* Called whenever some parameters have changed. */
    function refresh() {
        /* Disable the refresh button to prevent too many calls */
        $("#refresh").prop("disabled", true);
        /* Get the current parameter values */
        var params1 = gui.widget1.getParameters();
        var max = params1.zDataType == "BOARDINGS" ? 5
                : params1.zDataType == "WALK_DISTANCE" ? params1.maxWalkDistance * 1.2 : params1.maxTimeSec;
        var zDataTypeText = "le temps de parcours";
        if (params1.zDataType == "BOARDINGS")
            zDataTypeText = "le nb d'embarquements";
        else if (params1.zDataType == "WALK_DISTANCE")
            zDataTypeText = "la distance de marche";
        $("#legendHeader").text("Couleur selon " + zDataTypeText);
        /* Get a TimeGrid from the server. */
        gui.maxTimeSec = params1.maxTimeSec;
        params1.maxTimeSec += 300;
        gui.timeGrid = new otp.analyst.TimeGrid(params1).onLoad(function(timeGrid) {
            /* Create a ColorMap */
            gui.colorMap = new otp.analyst.ColorMap({
                max : max,
                zDataType : params1.zDataType
            });
            gui.colorMap.setLegendCanvas($("#legend").get(0));
            /* Clear old layers, add a new one. */
            gui.gradientLayerGroup.clearLayers();
            gui.layer = otp.analyst.TimeGrid.getLeafletLayer(gui.timeGrid, gui.colorMap);
            gui.layer.setOpacity(gui.opacity);
            gui.gradientLayerGroup.addLayer(gui.layer);
            gui.layer.bringToFront(); // TODO Leaflet bug?
            /* Re-enable refresh button */
            $("#refresh").prop("disabled", false);
            refreshHisto();
        });
        gui.gradientLayerGroup.clearLayers();

        /* Check if we should display vector isochrones. */
        var isoEnable = $("#isoEnable").is(":checked");
        $("#downloadIsoVector").prop("disabled", !isoEnable);
        gui.isochronesLayerGroup.clearLayers();
        if (isoEnable) {
            /* Get the cutoff times from the input, in minutes */
            var isotimes = [];
            var isostr = $("#cutoffSec").val().split(";");
            for (var i = 0; i < isostr.length; i++) {
                isotimes.push(parseInt(isostr[i]) * 60);
            }
            /* Get the isochrone GeoJSON features from the server */
            gui.isochrone = new otp.analyst.Isochrone(params1, isotimes).onLoad(function(iso) {
                gui.isochronesLayerGroup.clearLayers();
                for (var i = 0; i < isotimes.length; i++) {
                    var isoLayer = L.geoJson(iso.getFeature(isotimes[i]), {
                        style : {
                            color : "#0000FF",
                            weight : 1,
                            dashArray : (i % 2) == 1 ? "5,2" : "",
                            fillOpacity : 0.0,
                            fillColor : "#000000"
                        }
                    });
                    gui.isochronesLayerGroup.addLayer(isoLayer);
                }
            });
        }
    }

    function refreshHisto() {
        d3.select("#chart").selectAll("div").remove();
        if (!gui.population || !gui.timeGrid.isLoaded())
            return;
        if (gui.widget1.getParameters().zDataType != "TIME")
            return;
        /* Display histogram using D3 */
        var scorer = new otp.analyst.Scoring();
        var histo = scorer.histogram(gui.timeGrid, gui.population, 0, gui.maxTimeSec, 300);
        displayHistogram(histo, gui.populationDescriptor);
    }

    /* Plug the refresh callback function. */
    gui.widget1.onRefresh(refresh);
    $("#refresh").click(refresh);
    $("#isoEnable").click(refresh);
    /* Refresh to force an initial load. */
    gui.widget1.refresh();

    /* Initialize populations */
    initPopulations(function(result) {
        gui.populations = result;
        var populationDropdown = $("#populations");
        $.each(gui.populations, function(id, popDesc) {
            populationDropdown.append($("<option />").val(id).text(popDesc.name));
        });
        populationDropdown.change(function() {
            gui.populationDescriptor = gui.populations[this.value];
            gui.population = gui.populationDescriptor.load();
            gui.population.onLoad(function() {
                refreshHisto();
                gui.populationLayerGroup.clearLayers();
                $('#legendPop').hide();
                if (gui.population.size() < 1000) {
                    $('#legendPopLabel').text(gui.populationDescriptor.name);
                    $('#legendPop').show();
                    for (var i = 0; i < gui.population.size(); i++) {
                        var ind = gui.population.get(i);
                        var circleMarker = L.circleMarker(ind.location, {
                            color : 'red',
                            fillColor : '#f03',
                            fillOpacity : 0.5,
                            // Sqrt to have circle surface proportional to W
                            radius : 10 * Math.sqrt(ind.w / gui.population.getMaxW())
                        }).bindPopup(ind.name);
                        gui.populationLayerGroup.addLayer(circleMarker);
                    }
                }
            });
        });
        populationDropdown.trigger("change");
    });
    gui.population = null;

    /* Download image button */
    $('#downloadIsoimage').click(function() {
        var image = otp.analyst.TimeGrid.getImage(gui.timeGrid, gui.colorMap, {
            width : 2000, // TODO parameter
            // Default to map bounds
            southwest : gui.map.getBounds().getSouthWest(),
            northeast : gui.map.getBounds().getNorthEast()
        });
        window.open(image.src);
    });

    /* Download vector isochrone button */
    $("#downloadIsoVector").prop("disabled", true).click(function() {
        window.open(gui.isochrone.getUrl("iso.zip"));
    });

    /* About / contact buttons */
    $("#about").click(function() {
        $("#aboutContent").dialog({
            width : 800,
            height : 600
        }).show();
    });
    $("#contact").click(function() {
        $("#contactContent").dialog().show();
    });
});

function initPopulations(callback) {
    function loadFromCsv(filename, options) {
        var pop = new otp.analyst.Population();
        pop.loadFromCsv(filename, options);
        return pop;
    }
    function loadFromServer(id) {
        var pop = new otp.analyst.Population();
        pop.loadFromServer(id);
        return pop;
    }
    var ret = {};
    ret["individus"] = {
        name : "Population (nb individus)",
        load : function() {
            return loadFromCsv("data/insee_pop.csv", {
                latColName : "y",
                lonColName : "x",
                weightColName : "ind"
            });
        },
    };
    ret["emplois"] = {
        name : "Emplois (nombre)",
        load : function() {
            return loadFromCsv("data/geoetab13.csv", {
                latColName : "lat",
                lonColName : "lon",
                weightColName : "emplois"
            });
        },
        numberFormat : ".0f",
    };
    ret["nb_lycees"] = {
        name : "Lycées (nombre)",
        load : function() {
            return loadFromCsv("data/lycees.csv", {
                latColName : "Y",
                lonColName : "X",
                nameColName : "DESIGNATIO"
            });
        },
    };
    ret["nb_colleges"] = {
        name : "Collèges (nombre)",
        load : function() {
            return loadFromCsv("data/colleges.csv", {
                latColName : "Y",
                lonColName : "X",
                nameColName : "DESIGNATIO"
            });
        }
    };
    ret["pl_colleges"] = {
        name : "Collèges (places)",
        load : function() {
            return loadFromCsv("data/colleges.csv", {
                latColName : "Y",
                lonColName : "X",
                nameColName : "DESIGNATIO",
                weightColName : "TOTAL"
            });
        }
    };
    ret["mediatheques"] = {
        name : "Bibliothèques/Médiathèques (nombre)",
        load : function() {
            return loadFromCsv("data/mediatheques13.csv", {
                latColName : "lat",
                lonColName : "lon",
                nameColName : "nom"
            });
        }
    };
    ret["musees"] = {
        name : "Musées (nombre)",
        load : function() {
            return loadFromCsv("data/musees13.csv", {
                latColName : "lat",
                lonColName : "lon",
                nameColName : "nom"
            });
        }
    };
    ret["culture"] = {
        name : "Centre culturels (nombre)",
        load : function() {
            return loadFromCsv("data/culture13.csv", {
                latColName : "lat",
                lonColName : "lon",
                nameColName : "nom"
            });
        }
    };
    if (false) {
        otp.analyst.Population.listServerPointSets(function(pointsets) {
            $.each(pointsets, function(index, pointset) {
                ret[pointset.id] = {
                    name : pointset.id,
                    load : function() {
                        return loadFromServer(pointset.id);
                    }
                };
            });
            callback(ret);
        });
    } else {
        setTimeout(function() {
            callback(ret);
        }, 100);
    }
}

function displayHistogram(histo, popDesc) {
    var x = d3.scale.linear().domain([ 0, d3.max(histo, function(d) {
        return +d.w
    }) ]).range([ 0, 80 ]);
    var fmt = d3.format(popDesc.numberFormat || ".02f");
    var tooltip = d3.select("#chart").append("div").attr("class", "tooltip").text("-");
    d3.select("#chart").append("div").selectAll("div").data(histo).enter().append("div").attr("class", "bar").style(
            "width", function(d) {
                return x(d.w) + "%";
            }).text(function(d) {
        return fmt(d.w);
    }).on("mouseover", function(d) {
        var text = (d.t / 60) + " - " + ((d.t + 300) / 60) + " mn: " + fmt(d.w);
        tooltip.text(text);
    }).on('mouseout', function(d) {
        tooltip.text("-");
    });
}