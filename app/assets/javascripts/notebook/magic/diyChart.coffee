define([
    'observable'
    'knockout'
    'd3'
    'dimple'
], (Observable, ko, d3, dimple) ->
  (dataO, container, options) ->
    w = options.width||600
    h = options.height||400

    count = d3.select(container).append("p")
    count .selectAll("span .nrow")
          .data([options.nrow])
          .enter()
          .append("span")
          .attr("class", "nrow")
            .text( (d) ->
              d + " items"
            )
    count .selectAll("span .shown")
          .data([options.shown])
          .enter()
          .append("span")
          .attr("class", "shown")
          .attr("style", "color: red")
            .text( (d) ->
              console.log("d is " + d)
              if options.nrow > d
                " (Out of " + options.nrow + " items, only the " +  d + " first items are shown)"
              else
                ""
            )

    svg = d3.select(container).append("svg:svg").attr("width", w+"px").attr("height", h+"px").attr("id", "scatter"+@genId)
    chart = new dimple.chart(svg, @dataInit)
    chart.setBounds(w*(50/600), h*(20/400), w *(540/600), h*(350/400))

    try
      eval(options.js) # should create the `js` var function
    catch error
      alert("Error when evaluating function (see console for the error)")
      console.log(error)

    js(@dataInit, options.headers, chart)
    chart.draw()

    dataO.subscribe( (newData) =>
      chart.data = newData
      chart.draw(1000)
    )
)